/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{RequestContext, RouteResult}
import com.mongodb.client.model.Filters
import com.mongodb.spark.config.ReadConfig
import com.mongodb.spark.toSparkContextFunctions
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.bson.Document
import org.json4s.DefaultFormats
import org.json4s.jackson.Json

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.io.StdIn
import scala.language.postfixOps

object Main extends CORSHandler {

  val partyMatcher = Map("CDU" -> "CDU", "SPD" -> "SPD", "FDP" -> "FDP", "Linke" -> "Linke", "B90" -> "B90", "AfD" -> "AfD", "CSU" -> "CSU", "Parteilos" -> "Parteilos")

  def main(args: Array[String]): Unit = {

    implicit val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "my-system")
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext: ExecutionContextExecutor = system.executionContext


    val countTotalYearRDD = getRDD("countTotalByYear")
    val countTotalMonthRDD = getRDD("countTotalByMonth")
    val countTotalWeekRDD = getRDD("countTotalByWeek")


    val routes = {
      concat(
        getRoutesWithPartyYearMonth("countTweetByMonth", countTotalYearRDD, countTotalMonthRDD, countTotalWeekRDD)
      )
    }


    val bindingFuture = Http().newServerAt("0.0.0.0", 8080).bind(routes)


    println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
    StdIn.readLine() // let it run until user presses return
    bindingFuture
      .flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete(_ => system.terminate()) // and shutdown when done
  }


  /**
   * Liefert eine Mongo-Collection als RDD
   * @param collectionName Name der Mongo-Collection
   * @return RDD[Document]
   */
  def getRDD(collectionName: String): RDD[Document] = {
    val sparkSession = SparkSession.builder()
      .master("local")
      .appName("MongoSparkConnector")
      .config("spark.mongodb.input.uri", sys.env("REAGENT_MONGO") + "examples." + collectionName + "?authSource=examples")
      .config("spark.testing.memory", 2147480000)
      .getOrCreate()

    val sc = sparkSession.sparkContext
    sc.loadFromMongoDB(ReadConfig(Map("uri" -> (sys.env("REAGENT_MONGO") + "examples." + collectionName + "?authSource=examples")))).rdd
  }


  /**
   * Liefert Paths:
   * /path -> [{"CDU" : {"2017": 12234, "2018": 63578, ...}}, {"SPD": {...}}, ...]
   * /path/partei -> {"2017": 12234, "2018": 63578, ...}
   * /path/jahr/"week" -> [{"CDU" : {"01": 124, "02": 642, ...}}, {"SPD": {...}}, ...]
   * /path/jahr/"month" -> [{"CDU" : {"01": 1240, "02": 2425, ...}}, {"SPD": {...}}, ...]
   * /path/jahr/"week"/partei -> {"01": 124, "02": 642, ...}
   * /path/jahr/"month"/partei -> {"01": 1240, "02": 2425, ...}
   */
  def getRoutesWithCount(pathName: String, collectionYear: RDD[Document], collectionMonth: RDD[Document], collectionWeek: RDD[Document], timespan: String = ""): RequestContext => Future[RouteResult] = {

    var yearString = "year"
    var monthString = "month"
    var weekString = "week"

    if (timespan != "") {
      yearString = timespan
      monthString = timespan
      weekString = timespan
    }

    pathPrefix(pathName) {
      concat(
        pathEnd {
          get {
            corsHandler(complete(HttpEntity(ContentTypes.`application/json`, {
              val temp = collectionYear
                .map(elem => elem.get("_id").asInstanceOf[Document].get("party") -> (elem.get("_id").asInstanceOf[Document].get("year").toString, elem.get("count")))
                .groupBy(_._1)
                .mapValues(_.map(_._2).toMap)
                .collect()
              Json(DefaultFormats).write(temp)
            })))
          }
        },
        path(partyMatcher) { party =>
          get {
            corsHandler(complete(HttpEntity(ContentTypes.`application/json`, {
              val temp = collectionYear
                .filter(_.get("_id").asInstanceOf[Document].getString("party") == party)
                .map(elem => (elem.get("_id").asInstanceOf[Document].get("year").toString, elem.getInteger("count")))
                .collect()
                .toMap
              Json(DefaultFormats).write(temp)
            })))
          }
        },
        path(IntNumber / "month") { year =>
          get {
            corsHandler(complete(HttpEntity(ContentTypes.`application/json`, {
              val temp = collectionMonth
                .filter(_.get("_id").asInstanceOf[Document].getInteger("year") == year)
                .groupBy(_.get("_id").asInstanceOf[Document].get("party").toString)
                .mapValues(_.map(elem => (elem.get("_id").asInstanceOf[Document].getInteger("month").formatted("%02d"), elem.getInteger("count"))).toMap)
                .collect()
              Json(DefaultFormats).write(temp)
            })))
          }
        },
        path(IntNumber / "week") { year =>
          get {
            corsHandler(complete(HttpEntity(ContentTypes.`application/json`, {
              val temp = collectionWeek
                .filter(_.get("_id").asInstanceOf[Document].getInteger("year") == year)
                .groupBy(_.get("_id").asInstanceOf[Document].get("party").toString)
                .mapValues(_.map(elem => (elem.get("_id").asInstanceOf[Document].getInteger("week").formatted("%02d"), elem.getInteger("count"))).toMap)
                .collect()
              Json(DefaultFormats).write(temp)
            })))
          }
        },
        path(IntNumber / "month" / partyMatcher) { (year, party) =>
          get {
            corsHandler(complete(HttpEntity(ContentTypes.`application/json`, {
              val temp = collectionMonth
                .filter(_.get("_id").asInstanceOf[Document].getInteger("year") == year)
                .filter(_.get("_id").asInstanceOf[Document].getString("party") == party)
                .map(elem => (elem.get("_id").asInstanceOf[Document].getInteger("month").formatted("%02d"), elem.getInteger("count")))
                .collect()
                .toMap
              Json(DefaultFormats).write(temp)
            })))
          }
        },
        path(IntNumber / "week" / partyMatcher) { (year, party) =>
          get {
            corsHandler(complete(HttpEntity(ContentTypes.`application/json`, {
              val temp = collectionWeek
                .filter(_.get("_id").asInstanceOf[Document].getInteger("year") == year)
                .filter(_.get("_id").asInstanceOf[Document].getString("party") == party)
                .map(elem => (elem.get("_id").asInstanceOf[Document].getInteger("week").formatted("%02d"), elem.getInteger("count")))
                .collect()
                .toMap
              Json(DefaultFormats).write(temp)
            })))
          }
        }
        /*path(IntNumber / IntNumber) { (year, month) =>
          get {
            corsHandler(complete(HttpEntity(ContentTypes.`application/json`, collectionMonth.find(Filters.and(
              Filters.eq("_id.year", year),
              Filters.eq("_id.month", month))
            ).results().map(_.toJson()).toArray.mkString("[", ",", "]"))))
          }
        },
        path(IntNumber / IntNumber / partyMatcher) { (year, month, party) =>
          get {
            corsHandler(complete(HttpEntity(ContentTypes.`application/json`, collectionMonth.find(Filters.and(
              Filters.eq("_id.year", year),
              Filters.eq("_id.month", month),
              Filters.eq("_id.party", party))
            ).results().map(_.toJson()).toArray.mkString("[", ",", "]"))))
          }
        }*/
      )
    }
  }

  /**
   * Liefert Paths:
   * /path -> [{"CDU" : {"2017": 12234, "2018": 63578, ...}}, {"SPD": {...}}, ...]
   * /path/partei -> {"2017": 12234, "2018": 63578, ...}
   * /path/jahr/"week" -> [{"CDU" : {"01": 124, "02": 642, ...}}, {"SPD": {...}}, ...]
   * /path/jahr/"month" -> [{"CDU" : {"01": 1240, "02": 2425, ...}}, {"SPD": {...}}, ...]
   * /path/jahr/"week"/partei -> {"01": 124, "02": 642, ...}
   * /path/jahr/"month"/partei -> {"01": 1240, "02": 2425, ...}
   */
  def getRoutesWithStrings(pathName: String, collectionYear: RDD[Document], collectionMonth: RDD[Document], collectionWeek: RDD[Document], timespan: String = ""): RequestContext => Future[RouteResult] = {

    var yearString = "year"
    var monthString = "month"
    var weekString = "week"

    if (timespan != "") {
      yearString = timespan
      monthString = timespan
      weekString = timespan
    }

    pathPrefix(pathName) {
      concat(
        pathEnd {
          get {
            corsHandler(complete(HttpEntity(ContentTypes.`application/json`, {
              val temp = collectionYear
                .map(elem => elem.get("_id").asInstanceOf[Document].get("party") -> (elem.get("_id").asInstanceOf[Document].get("year").toString, elem.get("count")))
                .groupBy(_._1)
                .mapValues(_.map(_._2).toMap)
                .collect()
              Json(DefaultFormats).write(temp)
            })))
          }
        },
        path(partyMatcher) { party =>
          get {
            corsHandler(complete(HttpEntity(ContentTypes.`application/json`, {
              val temp = collectionYear
                .filter(_.get("_id").asInstanceOf[Document].getString("party") == party)
                .map(elem => (elem.get("_id").asInstanceOf[Document].get("year").toString, elem.getInteger("count")))
                .collect()
                .toMap
              Json(DefaultFormats).write(temp)
            })))
          }
        },
        path(IntNumber / "month") { year =>
          get {
            corsHandler(complete(HttpEntity(ContentTypes.`application/json`, {
              val temp = collectionMonth
                .filter(_.get("_id").asInstanceOf[Document].getInteger("year") == year)
                .groupBy(_.get("_id").asInstanceOf[Document].get("party").toString)
                .mapValues(_.map(elem => (elem.get("_id").asInstanceOf[Document].getInteger("month").formatted("%02d"), elem.getInteger("count"))).toMap)
                .collect()
              Json(DefaultFormats).write(temp)
            })))
          }
        },
        path(IntNumber / "week") { year =>
          get {
            corsHandler(complete(HttpEntity(ContentTypes.`application/json`, {
              val temp = collectionWeek
                .filter(_.get("_id").asInstanceOf[Document].getInteger("year") == year)
                .groupBy(_.get("_id").asInstanceOf[Document].get("party").toString)
                .mapValues(_.map(elem => (elem.get("_id").asInstanceOf[Document].getInteger("week").formatted("%02d"), elem.getInteger("count"))).toMap)
                .collect()
              Json(DefaultFormats).write(temp)
            })))
          }
        },
        path(IntNumber / "month" / partyMatcher) { (year, party) =>
          get {
            corsHandler(complete(HttpEntity(ContentTypes.`application/json`, {
              val temp = collectionMonth
                .filter(_.get("_id").asInstanceOf[Document].getInteger("year") == year)
                .filter(_.get("_id").asInstanceOf[Document].getString("party") == party)
                .map(elem => (elem.get("_id").asInstanceOf[Document].getInteger("month").formatted("%02d"), elem.getInteger("count")))
                .collect()
                .toMap
              Json(DefaultFormats).write(temp)
            })))
          }
        },
        path(IntNumber / "week" / partyMatcher) { (year, party) =>
          get {
            corsHandler(complete(HttpEntity(ContentTypes.`application/json`, {
              val temp = collectionWeek
                .filter(_.get("_id").asInstanceOf[Document].getInteger("year") == year)
                .filter(_.get("_id").asInstanceOf[Document].getString("party") == party)
                .map(elem => (elem.get("_id").asInstanceOf[Document].getInteger("week").formatted("%02d"), elem.getInteger("count")))
                .collect()
                .toMap
              Json(DefaultFormats).write(temp)
            })))
          }
        }
        /*path(IntNumber / IntNumber) { (year, month) =>
          get {
            corsHandler(complete(HttpEntity(ContentTypes.`application/json`, collectionMonth.find(Filters.and(
              Filters.eq("_id.year", year),
              Filters.eq("_id.month", month))
            ).results().map(_.toJson()).toArray.mkString("[", ",", "]"))))
          }
        },
        path(IntNumber / IntNumber / partyMatcher) { (year, month, party) =>
          get {
            corsHandler(complete(HttpEntity(ContentTypes.`application/json`, collectionMonth.find(Filters.and(
              Filters.eq("_id.year", year),
              Filters.eq("_id.month", month),
              Filters.eq("_id.party", party))
            ).results().map(_.toJson()).toArray.mkString("[", ",", "]"))))
          }
        }*/
      )
    }
  }

  /*def main(args: Array[String]): Unit = {
    val mongoClient: MongoClient = MongoClient(sys.env("REAGENT_MONGO") + "?authSource=examples")
    val database: MongoDatabase = mongoClient.getDatabase("examples")

    val collectionProcessed: MongoCollection[Document] = database.getCollection("ProcessedTweets")
    val collectionOriginal: MongoCollection[Document] = database.getCollection("tweets_bundestag_complete")

    val collectionCountTotalByYear: MongoCollection[Document] = database.getCollection("countTotalByYear")

    implicit val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "my-system")
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext: ExecutionContextExecutor = system.executionContext


    val routes = {
      concat(
        getRoutesWithPartyYearMonth("countTweetByMonth", collectionCountTotalByYear, collectionCountTotalByYear, collectionCountTotalByYear)
      )
    }


    val bindingFuture = Http().newServerAt("0.0.0.0", 8080).bind(routes)


    println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
    StdIn.readLine() // let it run until user presses return
    bindingFuture
      .flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete(_ => system.terminate()) // and shutdown when done
  }*/
}