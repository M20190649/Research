import org.slf4j.{Logger, LoggerFactory}
import org.joda.time.DateTime
import scala.io.Source
import scala.collection.mutable.ListBuffer
import scala.collection.SortedSet
import scala.util.control.Breaks._
import java.io.PrintWriter

object FlockChecker {
  private val logger: Logger = LoggerFactory.getLogger("myLogger")
	
  class Flock(val line: String) extends Ordered [Flock] {
    val temp = line.split(",")
    val start: Int = temp(0).toInt
    val end: Int = temp(1).toInt
    val pids: SortedSet[Long] = temp(2).split(" ").par.map(_.toLong).to[SortedSet]
    val n = pids.size
  
    override def toString = "%d,%d,%s".format(start, end, pids.mkString(" "))

    override def compare(that: Flock) = {
      if (this.n > that.n)
	1
      else if (this.n < that.n)
	-1
      else
	compareStream(this.pids.toStream, that.pids.toStream)
      }
  
    def compareStream(x: Stream[Long], y: Stream[Long]): Int = {
      (x.headOption, y.headOption) match {
	case (Some(xh), Some(yh))  => 
	  if (xh == yh) {
	    compareStream(x.tail, y.tail)
	  } else {
	    xh.compare(yh)
	  }
	case (Some(_), None) => 1
	case (None, Some(_)) => -1
	case (None, None) => 0
      }
    }
  }

  def saveSortedFile(path: String): String = {
    logger.info("Reading %s".format(path))
    val extension = path.split("\\.").last
    val path_without_extension = path.split("\\.").dropRight(1).mkString(".")
    var flocks: SortedSet[Flock] = SortedSet.empty
    val file = Source.fromFile(path)
    for (line <- file.getLines) {
      flocks += new Flock(line)
    }
    file.close()
    val sorted_path = "%s_sorted.%s".format(path_without_extension, extension)
    new PrintWriter(sorted_path) {
      write(flocks.mkString("\n"))
      close() 
    }
    file.close()
    logger.info("%s has been sorted as %s".format(path, sorted_path))

    sorted_path
  }

  def sortFile(path: String): SortedSet[Flock] = {
    logger.info("Reading %s".format(path))
    val extension = path.split("\\.").last
    val path_without_extension = path.split("\\.").dropRight(1).mkString(".")
    var flocks: SortedSet[Flock] = SortedSet.empty
    val file = Source.fromFile(path)
    for (line <- file.getLines) {
      if(line != "" | line != "\n"){
	flocks += new Flock(line)
      }
    }
    file.close()

    flocks
  }

  def compareFiles(path1: String, path2: String): Unit = {
    val flocks1 = sortFile(path1)
    val flocks2 = sortFile(path2)
    var notfound = new ListBuffer[String]()
    var hits = 0
    
    notfound += ""
    for(flock1 <- flocks1){
      var found = false
      for(flock2 <- flocks2){
	if(!found && flock2.pids.intersect(flock1.pids).sameElements(flock1.pids)){
	  found = true
	  hits = hits + 1
	}
      }
      if(!found){
	notfound += "%s\n".format(flock1.pids.mkString(" "))
      } 
    }
    val n = flocks1.size
    val p = (hits.toFloat / n) * 100
    logger.info("Percentage of found flocks: %.2f%% [%d/%d]".format(p, hits, n))
    new PrintWriter("/tmp/NotFound.flocks") {
      write(notfound.mkString(""))
      close() 
    }
  }
    	
  def main(args: Array[String]): Unit = {
    val path1 = args(0)
    var path2 = args(1)

    FlockChecker.compareFiles(path1,path2)
  }
}
