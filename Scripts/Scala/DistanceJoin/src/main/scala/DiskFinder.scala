package edu.ucr.dblab.djoin

import org.slf4j.{LoggerFactory, Logger}
import scala.collection.JavaConverters._
import org.apache.spark.SparkConf
import org.apache.spark.sql.{SparkSession}
import org.apache.spark.sql.catalyst.ScalaReflection
import org.apache.spark.sql.types.StructType
import org.apache.spark.serializer.KryoSerializer
import org.apache.spark.storage.StorageLevel
import org.apache.spark.rdd.RDD
import org.apache.spark.TaskContext
import org.datasyslab.geospark.serde.GeoSparkKryoRegistrator
import org.datasyslab.geospark.spatialRDD.{SpatialRDD, CircleRDD, PointRDD}
import org.datasyslab.geospark.spatialOperator.JoinQuery
import org.datasyslab.geospark.enums.{GridType, IndexType}
import org.datasyslab.geosparksql.utils.GeoSparkSQLRegistrator
import com.vividsolutions.jts.geom.{Geometry, Envelope, Coordinate, Point, Polygon, MultiPolygon}
import com.vividsolutions.jts.geom.GeometryFactory
import com.vividsolutions.jts.index.SpatialIndex
import com.vividsolutions.jts.index.quadtree._
import edu.ucr.dblab.Utils._
import edu.ucr.dblab.djoin.DistanceJoin.{geofactory, round, circle2point}

object DisksFinder{
  implicit val logger: Logger = LoggerFactory.getLogger("myLogger")

  case class ST_Point(id: Int, x: Double, y: Double, t: Int){
    def asWKT: String = s"POINT($x $y)\t$id\t$t\n"
  }

  def main(args: Array[String]): Unit = {
    logger.info("Starting session...")
    implicit val params = new DiskFinderConf(args)
    val appName = s"DiskFinder"
    implicit val debugOn = params.debug()
    implicit val spark = SparkSession.builder()
      .appName(appName)
      .config("spark.serializer", classOf[KryoSerializer].getName)
      .config("spark.kryo.registrator", classOf[GeoSparkKryoRegistrator].getName)
      .getOrCreate()
    GeoSparkSQLRegistrator.registerAll(spark)
    import spark.implicits._
    implicit val conf = spark.sparkContext.getConf
    def getConf(property: String)(implicit conf: SparkConf): String = conf.get(property)
    val appId: String = if(getConf("spark.master").contains("local")){
      getConf("spark.app.id")
    } else {
      getConf("spark.app.id").takeRight(4)
    }
    def header(msg: String): String = s"$appName|$appId|$msg|Time"
    def n(msg:String, count: Long): Unit = logger.info(s"$appName|$appId|$msg|Load|$count")
    logger.info("Starting session... Done!")

    val (pointsRaw, nPoints) = timer{"Reading points"}{
      val pointsSchema = ScalaReflection.schemaFor[ST_Point].dataType.asInstanceOf[StructType]
      val pointsInput = spark.read.schema(pointsSchema)
        .option("delimiter", "\t").option("header", false)
        .csv(params.points()).as[ST_Point]
        .rdd
      val pointsRaw = new SpatialRDD[Point]
      val pointsJTS = pointsInput.map{ point =>
        val userData = s"${point.id}\t${point.t}"
        val p = geofactory.createPoint(new Coordinate(point.x, point.y))
        p.setUserData(userData)
        p
      }
      pointsRaw.setRawSpatialRDD(pointsJTS)
      pointsRaw.analyze()
      pointsRaw.rawSpatialRDD.persist(StorageLevel.MEMORY_ONLY)
      val nPointsRaw = pointsRaw.rawSpatialRDD.count()
      n("Points", nPointsRaw)
      (pointsRaw, nPointsRaw)
    }

    val partitionStage = "Partitions done"
    val pointsRDD = timer{partitionStage}{
      pointsRaw.spatialPartitioning(GridType.QUADTREE, params.partitions())
      pointsRaw.spatialPartitionedRDD.persist(StorageLevel.MEMORY_ONLY)
      n(partitionStage, pointsRaw.spatialPartitionedRDD.count())
      pointsRaw
    }
    // Collecting the global settings...
    val global_grids = pointsRDD.partitionTree.getLeafZones.asScala.toVector
      .sortBy(_.partitionId).map(_.getEnvelope)
    implicit val settings = Settings(spark, logger, global_grids, debugOn)

    // Joining points...
    val considerBoundary = true
    val usingIndex = false
    val epsilon = params.epsilon() + params.precision()
    val method = params.method()
    val capacity = params.capacity()
    val joinStage = "Join done"

    val (joinRDD, nJoinRDD) = timer{ header(joinStage) }{
      val leftRDD = pointsRDD
      val rightRDD = new CircleRDD(pointsRDD, epsilon)
      rightRDD.analyze()
      rightRDD.spatialPartitioning(leftRDD.getPartitioner)

      val join = method match {
        case "Geospark" => { // GeoSpark distance join...
          DistanceJoin.join(leftRDD, rightRDD)
        }
        case "Baseline" => { // Baseline distance join...
          DistanceJoin.baseline(leftRDD, rightRDD)
        }
        case "Index" => { // Index based Quadtree ...
          DistanceJoin.indexBased(leftRDD, rightRDD)
        }
        case "Partition" => { // Partition based Quadtree ...
          DistanceJoin.partitionBasedByQuadtree(leftRDD, rightRDD, capacity)
        }
      }
      join.cache()
      val nJoin = join.count()
      n(joinStage, nJoin)
      (join, nJoin)
    }

    // Finding pairs and centers...
    val r2: Double = math.pow(epsilon / 2.0, 2)
    val centersStage = "Pairs and Centers found"
    val (pairsRDD, nPairsRDD, centersRDD, nCentersRDD) = timer{ header(centersStage) }{
      val pairs = joinRDD.map{ pair =>
        val id1 = pair._1.getUserData().toString().split("\t").head.trim().toInt
        val p1  = pair._1.getCentroid
        val id2 = pair._2.getUserData().toString().split("\t").head.trim().toInt
        val p2  = pair._2
        ( (id1, p1) , (id2, p2) )
      }
        .filter{ case(p1, p2) => p1._1 < p2._1 }
        .map{ case(p1, p2) => (p1._2, p2._2) }
      pairs.cache()
      val nPairs = pairs.count()
      n(centersStage, nPairs)

      val centers = pairs.flatMap{ case (p1, p2) =>
        calculateCenterCoordinates(p1, p2, r2)
      }
      centers.cache()
      val nCenters = centers.count()
      n(centersStage, nCenters)
      (pairs, nPairs, centers, nCenters)
    }

    pairsRDD.zipPartitions(centersRDD, preservesPartitioning = true) { case(pairsIt, centersIt) =>
      val pairs = pairsIt.flatMap { case(p1, p2) => List(p1, p2) }.size
      val centers = centersIt.size

      List( (pairs, centers) ).toIterator
    }.foreach(println)
    
    //
    debug{
      save("/tmp/edgesPoints.wkt"){
        pointsRDD.spatialPartitionedRDD.rdd.mapPartitionsWithIndex({ case(index, iter) =>
          iter.map{ point =>
            s"${point.toText()}\t${point.getUserData.toString}\t${index}\n"
          }}, preservesPartitioning = true)
          .collect().sorted
      }
      save{"/tmp/edgesGGrids.wkt"}{
        pointsRDD.partitionTree.getLeafZones.asScala.map{ z =>
          val id = z.partitionId
          val e = z.getEnvelope
          val p = DistanceJoin.envelope2polygon(e)
          s"${p.toText()}\t${id}\n"
        }
      }
    }

    //
    debug{
      save{"/tmp/edgesPairs.wkt"}{
        pairsRDD.map{ case(source, target) =>
          val coords = Array(source.getCoordinate, target.getCoordinate)
          val line = geofactory.createLineString(coords)
          s"${line.toText}\n"
        }.collect.sorted
      }
      save{"/tmp/edgesCenters.wkt"}{
        centersRDD.map{ center =>
          val circle = center.buffer(epsilon / 2.0, 100)
          s"${circle.toText}\n"
        }.collect.sorted
      }
    }
    
    logger.info("Closing session...")
    logger.info(s"${appId}|${System.getProperty("sun.java.command")} --npartitions ${global_grids.size}")
    spark.close()
    save{"/tmp/edgesLGrids.wkt"}{
      (0 until global_grids.size).flatMap{ n =>
        scala.io.Source
          .fromFile(s"/tmp/edgesLGrids_${n}.wkt")
          .getLines.map{ wkt =>
            s"$wkt\n"
          }
      }
    }
    logger.info("Closing session... Done!")
  }

  def calculateCenterCoordinates(p1: Point, p2: Point, r2: Double,
    delta: Double = 0.001): List[Point] = {

    val X: Double = p1.getX - p2.getX
    val Y: Double = p1.getY - p2.getY
    val D2: Double = math.pow(X, 2) + math.pow(Y, 2)
    if (D2 != 0.0){
      val root: Double = math.sqrt(math.abs(4.0 * (r2 / D2) - 1.0))
      val h1: Double = ((X + Y * root) / 2) + p2.getX
      val k1: Double = ((Y - X * root) / 2) + p2.getY
      val h2: Double = ((X - Y * root) / 2) + p2.getX
      val k2: Double = ((Y + X * root) / 2) + p2.getY
      val h = geofactory.createPoint(new Coordinate(h1,k1))
      val k = geofactory.createPoint(new Coordinate(h2,k2))
      List(h, k)
    } else {
      val p2_prime = geofactory.createPoint(new Coordinate(p2.getX + delta, p2.getY))
      calculateCenterCoordinates(p1, p2_prime, r2)
    }
  }  
}

