import org.slf4j.{Logger, LoggerFactory}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.simba.SimbaSession
import org.apache.spark.sql.simba.index.RTreeType
import org.apache.spark.sql.catalyst.ScalaReflection
import org.apache.spark.sql.types.StructType
import org.apache.spark.rdd.RDD
import scala.collection.mutable.ListBuffer

object FFGrid{
  private val logger: Logger = LoggerFactory.getLogger("myLogger")
  private val ST_Point_schema = ScalaReflection.schemaFor[ST_Point].dataType.asInstanceOf[StructType]
  case class ST_Point(pid: Int, x: Double, y: Double, t: Double)
  case class Grid9(gid: Int, points: List[ST_Point])
  
  def main(args: Array[String]) {
    val conf       = new ConfFF(args)
    val input      = conf.input()
    val epsilon    = conf.epsilon()
    val mu         = conf.mu()
    val debug      = conf.debug()
    val master     = conf.master()
    val partitions = conf.partitions()
    val cores      = conf.cores()
    val x_delta    = conf.grain_x()
    val y_delta    = conf.grain_y()
    val t_delta    = conf.grain_t()
    val precision  = 0.001

    logger.info("Logs started")

    // Starting session...
    var timer = System.currentTimeMillis()
    val simba = SimbaSession.builder().master(master).appName("FF")
      .config("simba.index.partitions", partitions)
      .config("spark.cores.max", cores)
      .getOrCreate()
    import simba.implicits._
    import simba.simbaImplicits._
    log("Session started", timer)

    // Indexing points...
    timer = System.currentTimeMillis()
    val points = simba.read.option("delimiter", "\t").option("header", "false").schema(ST_Point_schema).csv(input).as[ST_Point].cache()
    val nPoints = points.count()
    log("Points indexed", timer, nPoints, "points")

    // Computing the grid...
    timer = System.currentTimeMillis()
    val bounds = points.agg(min($"x").as("min_x"), max($"x").as("max_x"), min($"y").as("min_y"), max($"y").as("max_y"), min($"t").as("min_t"), max($"t").as("max_t"))
      .collect().map(s => (s.getDouble(0),s.getDouble(1),s.getDouble(2),s.getDouble(3),s.getDouble(4),s.getDouble(5))).head
    if(debug) logger.info(s"Bounds: ${bounds}")
    val cells = ((bounds._1 / x_delta).toInt to (bounds._2 / x_delta).toInt).toList
      .cross(((bounds._3 / y_delta).toInt to (bounds._4 / y_delta).toInt)).toList
      .cross(((bounds._5 / t_delta).toInt to (bounds._6 / t_delta).toInt)).toList.zipWithIndex
    val grid = simba.createDataset(cells)
      .map(c => (c._1._1._1,c._1._1._2,c._1._2,c._2))
      .toDF("x","y","t","gid").as("grid")
      .cache()
    val nGrid = grid.count().toInt
    log("Grid computed", timer, nGrid, "cells")

    // Indexing points by grid...
    timer = System.currentTimeMillis()
    val pointsByGrid = points.map{ p =>
        val x_prime = (p.x / x_delta).toInt
        val y_prime = (p.y / y_delta).toInt
        val t_prime = (p.t / t_delta).toInt
        (p.pid, p.x, p.y, p.t, x_prime, y_prime, t_prime)
      }
      .toDF("pid", "x", "y", "t", "x_prime", "y_prime", "t_prime")
      .index(RTreeType, "rT", Array("x_prime", "y_prime", "t_prime"))
      .as("points")
    val gridPoints = pointsByGrid.join(grid, col("points.x_prime") === col("grid.x") && col("points.y_prime") === col("grid.y") && col("points.t_prime") === col("grid.t"), "left")
      .map(p => (p.getInt(10), ST_Point(p.getInt(0), p.getDouble(1), p.getDouble(2), p.getDouble(3))))
      .rdd
      .partitionBy(new GridPartitioner(nGrid))
      .map(_._2)
    val nGridPoints = gridPoints.count()
    log("Points by grid indexed", timer, nGridPoints, "points")

    if(debug) logger.info(s"Number of partitions: ${gridPoints.getNumPartitions}")

    if(debug){
      val p = gridPoints.mapPartitionsWithIndex{ (index, data) =>
        data.map(d => s"$index,${d.x},${d.y},${d.t}\n")
      }
      savePartitions(p, "/tmp/p.csv")
    }

    // Extracting grid9's ...
    timer = System.currentTimeMillis()
    val grid9 = gridPoints.mapPartitions{ points =>
      val index = points.map{ p =>
        val x_index = (p.x/epsilon).toInt
        val y_index = (p.y/epsilon).toInt
        val t_index = (p.t).toInt
        ((x_index, y_index, t_index), List(p))
      }.toList
        .groupBy(_._1)
        .mapValues(seq => seq.map(_._2).reduce( (a, b) => a ++ b ))
        .toMap
      index.keys.map { k =>
        val i = k._1
        val j = k._2
        val t = k._3
        List(
          index.get((i-1, j-1, t)) , index.get((i, j-1, t)) , index.get((i+1, j-1, t)) ,
          index.get((i-1, j, t))   , index.get((i, j, t))   , index.get((i+1, j, t))   ,
          index.get((i-1, j+1, t)) , index.get((i, j+1, t)) , index.get((i+1, j+1, t))
        ).flatten.flatten
      }.toIterator
    }//.filter(g => g.size >= mu)

    val nGrid9 = grid9.count
    if(debug){
      grid9.take(2).foreach(println)
      grid9.map(g => g.filter(_.t == 0.0)).collect()
        .filter(_.size != 0)
        .map(g => g.map(p => s"${p.x} ${p.y}"))
        .map(g => s"MULTIPOINT(${g.mkString(",")})")
        //.foreach(println)
    }
    log("Grid9's extracted", timer, nGrid9)

    // Finding pairs...
    timer = System.currentTimeMillis()
    val r2: Double = math.pow(epsilon / 2.0, 2)
    val pairs = grid9.flatMap(g => g.cross(g))
      .filter(p => p._1.pid < p._2.pid)
      .map(p => (p, d(p._1, p._2)))
      .filter(p => p._2 <= epsilon + precision)
      .distinct()
    val nPairs = pairs.count()
    log("Pairs found", timer, nPairs, "pairs")
    if(debug){
      val p = pairs.map(_._1).map(p => s"LINESTRING(${p._1.x} ${p._1.y}, ${p._2.x} ${p._2.y})\n")
      saveLines(p, "/tmp/pairs.txt")
    }
    // Finding centers...
    timer = System.currentTimeMillis()
    val centers = pairs.map(p => calculateCenterCoordinates(p._1._1, p._1._2, r2))
    val nCenters = centers.count()
    log("Centers found", timer, nCenters, "centers")

    // Stopping session...
    println()
    simba.stop()
    logger.info("Session closed")
  }

  def d(p1: ST_Point, p2: ST_Point): Double = {
    scala.math.sqrt(scala.math.pow(p1.x - p2.x, 2.0) + scala.math.pow(p1.y - p2.y, 2.0) + scala.math.pow(p1.t - p2.t, 2.0))
  }

  def log(msg: String, timer: Long, n: Long = 0, tag: String = ""): Unit ={
    if(n == 0)
      logger.info("%-50s|%6.2f".format(msg,(System.currentTimeMillis()-timer)/1000.0))
    else
      logger.info("%-50s|%6.2f|%6d|%s".format(msg,(System.currentTimeMillis()-timer)/1000.0,n,tag))
  }

  def calculateCenterCoordinates(p1: ST_Point, p2: ST_Point, r2: Double): (ST_Point, ST_Point) = {
    var h = ST_Point(-1,0.0,0.0,0.0)
    var k = ST_Point(-2,0.0,0.0,0.0)
    val X: Double = p1.x - p2.x
    val Y: Double = p1.y - p2.y
    val D2: Double = math.pow(X, 2) + math.pow(Y, 2)
    if (D2 != 0.0){
      val root: Double = math.sqrt(math.abs(4.0 * (r2 / D2) - 1.0))
      val h1: Double = ((X + Y * root) / 2) + p2.x
      val k1: Double = ((Y - X * root) / 2) + p2.y
      val h2: Double = ((X - Y * root) / 2) + p2.x
      val k2: Double = ((Y + X * root) / 2) + p2.y
      h = ST_Point(p1.pid, h1, k1, p1.t)
      k = ST_Point(p2.pid, h2, k2, p2.t)
    }
    (h, k)
  }  

  import java.io._
  def savePartitions(data: RDD[String], filename: String): Unit ={
    val pw = new PrintWriter(new File(filename))
    pw.write(data.collect().mkString(""))
    pw.close
  }

  def saveLines(data: RDD[String], filename: String): Unit ={
    val pw = new PrintWriter(new File(filename))
    pw.write(data.collect().mkString(""))
    pw.close
  }

  implicit class Crossable[X](xs: Traversable[X]) {
    def cross[Y](ys: Traversable[Y]) = for { x <- xs; y <- ys } yield (x, y)
  }

  import org.apache.spark.Partitioner

  class GridPartitioner(partitions: Int) extends Partitioner{
    override def numPartitions: Int = partitions

    override def getPartition(key: Any): Int = {
      key.asInstanceOf[Int]
    }
  }

}