import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.sql.expressions._
import scala.collection.JavaConverters._
import org.slf4j.{Logger, LoggerFactory}
import org.rogach.scallop.{ScallopConf, ScallopOption}
import java.io._

case class ST_Point(pid: Int, x: Double, y: Double, t: Int)

object BerlinCopier {
  private val logger : Logger = LoggerFactory.getLogger("myLogger")

  def main(args: Array[String]): Unit = {
    val conf = new BCConf(args)
    val gap: Int = conf.gap()
    val indices: List[(Int, Int)] = List((1,0), (1,1), (0,1), (-1,1), (-1,0), (-1,-1), (0,-1), (1,-1))
    val spark = SparkSession.builder().master("local[*]").getOrCreate()

    import spark.implicits._
    val filename = conf.input()
    val data = spark.read.option("header", false)
      .option("delimiter", "\t").csv(filename)
      .map{ p =>
        (p.getString(0).toInt, p.getString(1).toDouble, p.getString(2).toDouble, p.getString(3).toInt)
      }.toDF("pid","x","y","t").as[ST_Point]

    val nData = data.count()
    logger.info(s"Size of the original dataset: $nData points")

    val boundary = data.agg(min($"x"), min($"y"), max($"x"), max($"y")).collect()(0)
    val min_x = boundary.getDouble(0)
    val min_y = boundary.getDouble(1)
    val max_x = boundary.getDouble(2)
    val max_y = boundary.getDouble(3)

    logger.info(s"Extension: (${min_x} ${min_y}), (${max_x} ${max_y})")

    val extent_x = math.ceil(max_x - min_x).toInt + gap
    val extent_y = math.ceil(max_y - min_y).toInt + gap

    logger.info(s"Dimensions: $extent_x x $extent_y")

    val max_pid = data.agg(max($"pid")).collect()(0).getInt(0) + 1

    logger.info(s"Max pid in this dataset: $max_pid")

    logger.info("Starting duplication...")
    var timer = System.currentTimeMillis()
    var duplication = spark.sparkContext.emptyRDD[ST_Point].toDS()
    val times = conf.times()
    if(times == 1){
      duplication = data
    } else {
      val n = times - 2
      duplication = (0 to n).par.map{ k =>
        val i = indices(k)._1
        val j = indices(k)._2

        data.map{ p =>
          val new_pid = p.pid + ((k+1) * max_pid)
          val new_x   = p.x + (i * extent_x)
          val new_y   = p.y + (j * extent_y)
          ST_Point(new_pid, new_x, new_y, p.t)
        }
      }.reduce( (a, b) => a.union(b))
      .union(data)
      .sort($"pid", $"t").persist()
    }
    logger.info(s"Duplication done at ${(System.currentTimeMillis() - timer) / 1000.0}s")

    logger.info("Saving new dataset...")
    timer = System.currentTimeMillis()
    val nDataset = duplication.count()
    logger.info(s"Size of the new dataset: $nDataset points")
    if(conf.debug()){
      val out = duplication.map(p => (p.pid, p.t, s"${p.x} ${p.y}"))
        .toDF("pid", "t", "point").sort($"pid", $"t")
        .withColumn("traj", collect_list($"point").over(Window.partitionBy($"pid")))
        .map(t => (t.getInt(0), t.getList[String](3).asScala))
        .map(t => (t._1, s"LINESTRING(${t._2.mkString(",")})"))
        .toDF("pid", "traj").distinct().orderBy($"pid")

      out.show(false)
      val pw = new PrintWriter(new File("/tmp/output.wkt"))
      pw.write(out.map(t => s"${t.getInt(0)};${t.getString(1)}\n").collect().mkString(""))
      pw.close
    }
    val pw = new PrintWriter(new File(conf.output()))
    pw.write(duplication.map(p => s"${p.pid}\t${p.x}\t${p.y}\t${p.t}\n").collect().mkString(""))
    pw.close
    logger.info(s"Dataset saved at ${(System.currentTimeMillis() - timer) / 1000.0}s")

    spark.close()
  }
}

class BCConf(args: Seq[String]) extends ScallopConf(args) {
  val input:  ScallopOption[String]   = opt[String]  (required = true)
  val gap:    ScallopOption[Int]      = opt[Int]     (default  = Some(500))
  val times:  ScallopOption[Int]      = opt[Int]     (default  = Some(2))
  val debug:  ScallopOption[Boolean]  = opt[Boolean] (default  = Some(false))
  val output: ScallopOption[String]   = opt[String]  (default  = Some("/tmp/output.tsv"))

  verify()
}
