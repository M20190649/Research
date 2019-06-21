import org.slf4j.{LoggerFactory, Logger}
import org.apache.spark.serializer.KryoSerializer
import org.apache.spark.storage.StorageLevel
import org.apache.spark.{SparkConf, SparkContext}
import org.datasyslab.geospark.enums.{FileDataSplitter, GridType, IndexType}
import org.datasyslab.geospark.spatialOperator.JoinQuery
import org.datasyslab.geospark.spatialPartitioning.GridPartitioner
import org.datasyslab.geospark.spatialRDD.{SpatialRDD, PolygonRDD, CircleRDD, PointRDD}
import org.datasyslab.geospark.serde.GeoSparkKryoRegistrator
import com.vividsolutions.jts.index.strtree.STRtree
import com.vividsolutions.jts.operation.buffer.BufferParameters
import com.vividsolutions.jts.geom.{GeometryFactory, Geometry, Envelope, Coordinate, Polygon, LinearRing, Point}
import com.vividsolutions.jts.geom.impl.CoordinateArraySequence
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import scala.collection.JavaConverters._
import SPMF.{AlgoLCM2, Transactions, Transaction}
import org.rogach.scallop._

object MF_Scaleup{
  private val logger: Logger = LoggerFactory.getLogger("myLogger")
  private val geofactory: GeometryFactory = new GeometryFactory();
  private val precision: Double = 0.001
  private var tag: String = ""
  private var appID: String = "app-00000000000000-0000"
  private var startTime: Long = clocktime
  private var cores: Int = 0
  private var executors: Int = 0

  def run(spark: SparkSession, points: PointRDD, MFPartitioner: GridPartitioner, params: FFConf, info: String = ""): (RDD[String], Long) = {
    import spark.implicits._

    appID     = spark.sparkContext.applicationId
    startTime = spark.sparkContext.startTime
    cores     = params.cores()
    executors = params.executors()
    val debug: Boolean    = params.mfdebug()
    val epsilon: Double   = params.epsilon()
    val mu: Int           = params.mu()
    val sespg: String     = params.sespg()
    val tespg: String     = params.tespg()
    val spatial: String   = params.spatial()
    if(params.tag() == ""){ tag = s"$info"} else { tag = s"${params.tag()}|${info}" }
    var Dpartitions: Int  = (cores * executors) * params.dpartitions()

    // Indexing points...
    val localStart = clocktime
    var timer = System.currentTimeMillis()
    var stage = "A.Points indexed"
    logStart(stage)
    points.spatialPartitioning(GridType.KDBTREE, Dpartitions)
    if(debug){
      val gridWKT = points.getPartitioner.getGrids.asScala.map(e => s"${envelope2Polygon(e).toText()}\n").mkString("")
      val f = new java.io.PrintWriter("/tmp/pairsGrid.wkt")
      f.write(gridWKT)
      f.close()
    }
    val pointsBuffer = new CircleRDD(points, epsilon + precision)
    pointsBuffer.analyze()
    pointsBuffer.spatialPartitioning(points.getPartitioner)
    points.buildIndex(IndexType.QUADTREE, true) // QUADTREE works better as an indexer than RTREE...
    points.indexedRDD.cache()
    points.spatialPartitionedRDD.cache()
    logEnd(stage, timer, points.rawSpatialRDD.count())

    // Finding pairs...
    timer = System.currentTimeMillis()
    stage = "B.Pairs found"
    logStart(stage)
    val considerBoundary = true
    val usingIndex = true
    val pairs = JoinQuery.DistanceJoinQueryFlat(points, pointsBuffer, usingIndex, considerBoundary)
      .rdd.map{ pair =>
        val id1 = pair._1.getUserData().toString().split("\t").head.trim().toInt
        val p1  = pair._1.getCentroid
        val id2 = pair._2.getUserData().toString().split("\t").head.trim().toInt
        val p2  = pair._2
        ( (id1, p1) , (id2, p2) )
      }.filter(p => p._1._1 < p._2._1)
    val nPairs = pairs.count()
    logEnd(stage, timer, nPairs)

    // Finding centers...
    timer = System.currentTimeMillis()
    stage = "C.Centers found"
    logStart(stage)
    val r2: Double = math.pow(epsilon / 2.0, 2)
    val centersPairs = pairs.map{ p =>
        val p1 = p._1._2
        val p2 = p._2._2
        calculateCenterCoordinates(p1, p2, r2)
    }
    val centers = centersPairs.map(_._1).union(centersPairs.map(_._2))
    val nCenters = centers.count()
    logEnd(stage, timer, nCenters)

    // Finding disks...
    timer = System.currentTimeMillis()
    stage = "D.Disks found"
    logStart(stage)
    val r = epsilon / 2.0
    val centersRDD = new PointRDD(centers.toJavaRDD(), StorageLevel.MEMORY_ONLY, sespg, tespg)
    val centersBuffer = new CircleRDD(centersRDD, r + precision)
    centersBuffer.spatialPartitioning(points.getPartitioner)
    val disks = JoinQuery.DistanceJoinQueryFlat(points, centersBuffer, usingIndex, considerBoundary)
      .rdd.map{ d =>
        val c = d._1.getEnvelope.getCentroid
        val pid = d._2.getUserData().toString().split("\t").head.trim()
        d._2.setUserData(s"$pid")
        (c, Array(d._2))
      }.reduceByKey( (pids1,pids2) => pids1 ++ pids2)
      .map(_._2)
      .filter(d => d.length >= mu)
      .map{ d =>
        val pids = d.map(_.getUserData.toString().toInt).sorted.mkString(" ")
        val centroid = geofactory.createMultiPoint(d).getEnvelope().getCentroid
        centroid.setUserData(pids)
        centroid
      }.distinct()
    val nDisks = disks.count()
    logEnd(stage, timer, nDisks)

    // Partition disks...
    timer = System.currentTimeMillis()
    stage = "E.Disks partitioned"
    logStart(stage)
    val diskCenters = new PointRDD(disks.toJavaRDD(), StorageLevel.MEMORY_ONLY, sespg, tespg)
    val diskCircles = new CircleRDD(diskCenters, r + precision)
    diskCircles.spatialPartitioning(MFPartitioner)
    diskCircles.spatialPartitionedRDD.cache()
    val nDisksRDD = diskCircles.spatialPartitionedRDD.count()
    logEnd(stage, timer, nDisksRDD)

    if(debug){
      val gridWKT = diskCircles.getPartitioner.getGrids.asScala.map(e => s"${envelope2Polygon(e).toText()}\n").mkString("")
      val f = new java.io.PrintWriter("/tmp/disksGrid.wkt")
      f.write(gridWKT)
      f.close()
    }

    // Finding maximal disks...
    timer = System.currentTimeMillis()
    stage = "F.Maximal disks found"
    logStart(stage)
    val grids  = MFPartitioner.getGrids.asScala.toList.zipWithIndex.map(g => g._2 -> g._1).toMap
    val nGrids = MFPartitioner.getGrids.size
    logger.info(s"Cell dimensions: ${grids(0).getWidth} x ${grids(0).getHeight}")
    logger.info(s"Cell area: ${grids(0).getArea}")
    val maximals = diskCircles.spatialPartitionedRDD.rdd
      .mapPartitionsWithIndex{ (i, disks) =>
        var result = List.empty[String]
        if(i >= nGrids && spatial == "CUSTOM"){
        } else {
          val transactions = disks.map{ d =>
            val x = d.getCenterPoint.x
            val y = d.getCenterPoint.y
            val pids = d.getUserData.toString()
            new Transaction(x, y, pids)
          }.toList.asJava
          val LCM = new AlgoLCM2()
          val data = new Transactions(transactions, 0)
          LCM.run(data)
          result = LCM.getPointsAndPids.asScala
            .map{ p =>
              val pids = p.getItems.mkString(" ")
              val grid = grids(i)
              val point = geofactory.createPoint(new Coordinate(p.getX, p.getY))
              
              val flag = isNotInExpansionArea(point, grid, 0.0)
              ((pids, p.getX, p.getY),  flag)
            }
            .filter(_._2).map(_._1)
            .map(p => s"${p._1}\t${p._2}\t${p._3}\n").toList
        }
        result.toIterator
      }.cache()
    val nMaximals = maximals.count()
    logEnd(stage, timer, nMaximals)
    val localEnd = clocktime
    val executionTime = (localEnd - localStart) / 1000.0
    logger.info(s"MAXIMALS|$appID|$cores|$executors|$epsilon|$mu|$nGrids|$executionTime|$nMaximals")

    (maximals, nMaximals)
  }

  def envelope2Polygon(e: Envelope): Polygon = {
    val minX = e.getMinX()
    val minY = e.getMinY()
    val maxX = e.getMaxX()
    val maxY = e.getMaxY()
    val p1 = new Coordinate(minX, minY)
    val p2 = new Coordinate(minX, maxY)
    val p3 = new Coordinate(maxX, maxY)
    val p4 = new Coordinate(maxX, minY)
    val coordArraySeq = new CoordinateArraySequence( Array(p1,p2,p3,p4,p1), 2)
    val ring = new LinearRing(coordArraySeq, geofactory)
    new Polygon(ring, null, geofactory)
  }

  def calculateCenterCoordinates(p1: Point, p2: Point, r2: Double): (Point, Point) = {
    var h = geofactory.createPoint(new Coordinate(-1.0,-1.0))
    var k = geofactory.createPoint(new Coordinate(-1.0,-1.0))
    val X: Double = p1.getX - p2.getX
    val Y: Double = p1.getY - p2.getY
    val D2: Double = math.pow(X, 2) + math.pow(Y, 2)
    if (D2 != 0.0){
      val root: Double = math.sqrt(math.abs(4.0 * (r2 / D2) - 1.0))
      val h1: Double = ((X + Y * root) / 2) + p2.getX
      val k1: Double = ((Y - X * root) / 2) + p2.getY
      val h2: Double = ((X - Y * root) / 2) + p2.getX
      val k2: Double = ((Y + X * root) / 2) + p2.getY
      h = geofactory.createPoint(new Coordinate(h1,k1))
      k = geofactory.createPoint(new Coordinate(h2,k2))
    }
    (h, k)
  }

  def isNotInExpansionArea(p: Point, e: Envelope, epsilon: Double): Boolean = {
    val error = 0.00000000001
    val x = p.getX
    val y = p.getY
    val min_x = e.getMinX - error
    val min_y = e.getMinY - error
    val max_x = e.getMaxX
    val max_y = e.getMaxY

    x <= (max_x - epsilon) &&
      x >= (min_x + epsilon) &&
      y <= (max_y - epsilon) &&
      y >= (min_y + epsilon)
  }

  def castEnvelopeInt(x: Any): Tuple2[Envelope, Int] = {
    x match {
      case (e: Envelope, i: Int) => Tuple2(e, i)
    }
  }

  def castStringBoolean(x: Any): Tuple2[String, Boolean] = {
    x match {
      case (s: String, b: Boolean) => Tuple2(s, b)
    }
  }

  def clocktime = System.currentTimeMillis()

  def logEnd(msg: String, timer: Long, n: Long): Unit ={
    val duration = (clocktime - startTime) / 1000.0
    logger.info("MF|%-30s|%6.2f|%-50s|%6.2f|%6d|%s".format(s"$appID|$executors|$cores|  END", duration, msg, (System.currentTimeMillis()-timer)/1000.0, n, tag))
  }

  def logStart(msg: String): Unit ={
    val duration = (clocktime - startTime) / 1000.0
    logger.info("MF|%-30s|%6.2f|%-50s|%6.2f|%6d|%s".format(s"$appID|$executors|$cores|START", duration, msg, 0.0, 0, tag))
  }

  import java.io._
  def saveLines(data: RDD[String], filename: String): Unit ={
    val pw = new PrintWriter(new File(filename))
    pw.write(data.collect().mkString(""))
    pw.close
  }
  def saveList(data: List[String], filename: String): Unit ={
    val pw = new PrintWriter(new File(filename))
    pw.write(data.mkString(""))
    pw.close
  }
  def saveText(data: String, filename: String): Unit ={
    val pw = new PrintWriter(new File(filename))
    pw.write(data)
    pw.close
  }

  import org.apache.spark.Partitioner
  class ExpansionPartitioner(partitions: Int) extends Partitioner{
    override def numPartitions: Int = partitions

    override def getPartition(key: Any): Int = {
      key.asInstanceOf[Int]
    }
  }

  import Numeric.Implicits._
  def mean[T: Numeric](xs: Iterable[T]): Double = xs.sum.toDouble / xs.size

  def variance[T: Numeric](xs: Iterable[T]): Double = {
    val avg = mean(xs)
    xs.map(_.toDouble).map(a => math.pow(a - avg, 2)).sum / xs.size
  }

  def stdDev[T: Numeric](xs: Iterable[T]): Double = math.sqrt(variance(xs))

  def roundAt(p: Int)(n: Double): Double = { val s = math pow (10, p); (math round n * s) / s }

  implicit class Crossable[X](xs: Traversable[X]) {
    def cross[Y](ys: Traversable[Y]) = for { x <- xs; y <- ys } yield (x, y)
  }

  def getPartitionerByCellNumber(boundary: Envelope, epsilon: Double, x: Double, y: Double): GridPartitioner = {
    val dx = boundary.getWidth / x
    val dy = boundary.getHeight / y
    getPartitionerByCellSize(boundary, epsilon, dx, dy)
  }

  def getPartitionerByCellSize(boundary: Envelope, epsilon: Double, dx: Double, dy: Double): GridPartitioner = {
    val minx = boundary.getMinX
    val miny = boundary.getMinY
    val maxx = boundary.getMaxX
    val maxy = boundary.getMaxY
    val Xs = (minx until maxx by dx).map(x => roundAt(3)(x))
    val Ys = (miny until maxy by dy).map(y => roundAt(3)(y))
    val g = Xs cross Ys
    val error = 0.0000001
    val grids = g.toList.map(g => new Envelope(g._1, g._1 + dx - error, g._2, g._2 + dy - error))
    logger.info(s"Number of grid envelops: ${grids.size}")
    new GridPartitioner(grids.asJava, epsilon, dx, dy, Xs.size, Ys.size)
  }

  /***
   * The main function...
   **/
  def main(args: Array[String]) = {
    val params: FFConf      = new FFConf(args)
    val master      = params.master()
    val port        = params.port()
    val portUI      = params.portui()
    val input       = params.input()
    val offset      = params.offset()
    val sepsg       = params.sespg()
    val tepsg       = params.tespg()
    val info        = params.info()
    val timestamp   = params.timestamp()
    val epsilon     = params.epsilon()
    cores       = params.cores()
    executors   = params.executors()
    val Dpartitions = (cores * executors) * params.dpartitions()

    // Starting session...
    var timer = clocktime
    var stage = "Session started"
    logStart(stage)
    val spark = SparkSession.builder()
      .config("spark.default.parallelism", 3 * cores * executors)
      .config("spark.serializer",classOf[KryoSerializer].getName)
      .config("spark.kryo.registrator", classOf[GeoSparkKryoRegistrator].getName)
      .config("spark.scheduler.mode", "FAIR")
      .config("spark.cores.max", cores * executors)
      .config("spark.executor.cores", cores)
      .master(s"spark://${master}:${port}")
      .appName("MaximalFinder")
      .getOrCreate()
    import spark.implicits._
    appID = spark.sparkContext.applicationId
    startTime = spark.sparkContext.startTime
    logEnd(stage, timer, 0)

    // Reading data...
    timer = System.currentTimeMillis()
    stage = "Data read"
    logStart(stage)
    var points = new PointRDD(spark.sparkContext, input, offset, FileDataSplitter.TSV, true, Dpartitions)
    if(timestamp >= 0){
      points = new PointRDD(points.rawSpatialRDD.rdd.filter{p =>
        val arr = p.getUserData.toString().split("\t")
        val t = arr(1).toInt
        t == timestamp
      }.toJavaRDD(), StorageLevel.MEMORY_ONLY, sepsg, tepsg)
    }
    points.CRSTransform(sepsg, tepsg)
    val nPoints = points.rawSpatialRDD.count()
    logEnd(stage, timer, nPoints)

    // Custom partitioner...
    timer = clocktime
    stage = "Custom partitioner"
    logStart(stage)
    points.analyze()
    val boundary = points.boundary()
    val dx = params.mfcustomx()
    val dy = params.mfcustomy()
    val MFPartitioner = getPartitionerByCellSize(boundary, epsilon, dx, dy)
    logEnd(stage, timer, MFPartitioner.getGrids.size)

    timer = clocktime
    stage = "Default partitioner"
    logStart(stage)
    points.analyze()
    points.spatialPartitioning(GridType.KDBTREE, Dpartitions)
    points.spatialPartitionedRDD.cache()
    val nPartitions = points.spatialPartitionedRDD.rdd.getNumPartitions
    logger.info(s"Number of partitions: $nPartitions")
    logEnd(stage, timer, nPartitions)

    // Running maximal finder...
    timer = System.currentTimeMillis()
    stage = "Maximal finder run"
    logStart(stage)
    val maximals = MF_Scaleup.run(spark, points, MFPartitioner, params)
    logEnd(stage, timer, maximals._2)

    // Closing session...
    timer = System.currentTimeMillis()
    stage = "Session closed"
    logStart(stage)
    if(info){
      InfoTracker.master = master
      InfoTracker.port = portUI
      InfoTracker.applicationID = appID
      InfoTracker.executors = executors
      InfoTracker.cores = cores
      val app_count = appID.split("-").reverse.head
      val f = new java.io.PrintWriter(s"${params.output()}app-${app_count}_info.tsv")
      f.write(InfoTracker.getExectutorsInfo())
      f.write(InfoTracker.getStagesInfo())
      f.write(InfoTracker.getTasksInfo())
      f.close()
    }
    spark.close()
    logEnd(stage, timer, 0)
  }  
}