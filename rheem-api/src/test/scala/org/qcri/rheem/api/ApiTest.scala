package org.qcri.rheem.api

import java.io.File
import java.net.URI
import java.nio.file.{Files, Paths}
import java.sql.{Connection, Statement}
import java.util.function.Consumer

import org.junit.{Assert, Test}
import org.qcri.rheem.basic.RheemBasics
import org.qcri.rheem.core.api.{Configuration, RheemContext}
import org.qcri.rheem.core.function.FunctionDescriptor.ExtendedSerializablePredicate
import org.qcri.rheem.core.function.{ExecutionContext, TransformationDescriptor}
import org.qcri.rheem.core.util.fs.LocalFileSystem
import org.qcri.rheem.java.Java
import org.qcri.rheem.java.operators.JavaMapOperator
import org.qcri.rheem.spark.Spark
import org.qcri.rheem.sqlite3.Sqlite3
import org.qcri.rheem.sqlite3.operators.Sqlite3TableSource

/**
  * Tests the Rheem API.
  */
class ApiTest {

  @Test
  def testReadMapCollect(): Unit = {
    // Set up RheemContext.
    val rheem = new RheemContext().withPlugin(Java.basicPlugin).withPlugin(Spark.basicPlugin)

    // Generate some test data.
    val inputValues = (for (i <- 1 to 10) yield i).toArray

    // Build and execute a Rheem plan.
    val outputValues = rheem
      .loadCollection(inputValues).withName("Load input values")
      .map(_ + 2).withName("Add 2")
      .collect()

    // Check the outcome.
    val expectedOutputValues = inputValues.map(_ + 2)
    Assert.assertArrayEquals(expectedOutputValues, outputValues.toArray)
  }

  @Test
  def testCustomOperator(): Unit = {
    // Set up RheemContext.
    val rheem = new RheemContext().withPlugin(Java.basicPlugin).withPlugin(Spark.basicPlugin)

    // Generate some test data.
    val inputValues = (for (i <- 1 to 10) yield i).toArray

    // Build and execute a Rheem plan.
    val inputDataSet = rheem.loadCollection(inputValues).withName("Load input values")

    // Add the custom operator.
    val IndexedSeq(addedValues) = rheem.customOperator(new JavaMapOperator(
      dataSetType[Int],
      dataSetType[Int],
      new TransformationDescriptor(
        toSerializableFunction[Int, Int](_ + 2),
        basicDataUnitType[Int], basicDataUnitType[Int]
      )
    ), inputDataSet)
    addedValues.withName("Add 2")

    // Collect the result.
    val outputValues = addedValues.asInstanceOf[DataQuanta[Int]].collect()

    // Check the outcome.
    val expectedOutputValues = inputValues.map(_ + 2)
    Assert.assertArrayEquals(expectedOutputValues, outputValues.toArray)
  }

  @Test
  def testCustomOperatorShortCut(): Unit = {
    // Set up RheemContext.
    val rheem = new RheemContext().withPlugin(Java.basicPlugin).withPlugin(Spark.basicPlugin)

    // Generate some test data.
    val inputValues = (for (i <- 1 to 10) yield i).toArray

    // Build and execute a Rheem plan.
    val outputValues = rheem
      .loadCollection(inputValues).withName("Load input values")
      .customOperator[Int](new JavaMapOperator(
      dataSetType[Int],
      dataSetType[Int],
      new TransformationDescriptor(
        toSerializableFunction[Int, Int](_ + 2),
        basicDataUnitType[Int], basicDataUnitType[Int]
      )
    )).withName("Add 2")
      .collect()

    // Check the outcome.
    val expectedOutputValues = inputValues.map(_ + 2)
    Assert.assertArrayEquals(expectedOutputValues, outputValues.toArray)
  }

  @Test
  def testWordCount(): Unit = {
    // Set up RheemContext.
    val rheem = new RheemContext().withPlugin(Java.basicPlugin).withPlugin(Spark.basicPlugin)

    // Generate some test data.
    val inputValues = Array("Big data is big.", "Is data big data?")

    // Build and execute a word count RheemPlan.
    val wordCounts = rheem
      .loadCollection(inputValues).withName("Load input values")
      .flatMap(_.split("\\s+")).withName("Split words")
      .map(_.replaceAll("\\W+", "").toLowerCase).withName("To lowercase")
      .map((_, 1)).withName("Attach counter")
      .reduceByKey(_._1, (a, b) => (a._1, a._2 + b._2)).withName("Sum counters")
      .collect().toSet

    val expectedWordCounts = Set(("big", 3), ("is", 2), ("data", 3))

    Assert.assertEquals(expectedWordCounts, wordCounts)
  }

  @Test
  def testWordCountOnSparkAndJava(): Unit = {
    // Set up RheemContext.
    val rheem = new RheemContext().withPlugin(Java.basicPlugin).withPlugin(Spark.basicPlugin)

    // Generate some test data.
    val inputValues = Array("Big data is big.", "Is data big data?")

    // Build and execute a word count RheemPlan.
    val wordCounts = rheem
      .loadCollection(inputValues).withName("Load input values").withTargetPlatforms(Java.platform)
      .flatMap(_.split("\\s+")).withName("Split words").withTargetPlatforms(Java.platform)
      .map(_.replaceAll("\\W+", "").toLowerCase).withName("To lowercase").withTargetPlatforms(Spark.platform)
      .map((_, 1)).withName("Attach counter").withTargetPlatforms(Spark.platform)
      .reduceByKey(_._1, (a, b) => (a._1, a._2 + b._2)).withName("Sum counters").withTargetPlatforms(Spark.platform)
      .collect().toSet

    val expectedWordCounts = Set(("big", 3), ("is", 2), ("data", 3))

    Assert.assertEquals(expectedWordCounts, wordCounts)
  }

  @Test
  def testSample(): Unit = {
    // Set up RheemContext.
    val rheem = new RheemContext().withPlugin(Java.basicPlugin).withPlugin(Spark.basicPlugin)

    // Generate some test data.
    val inputValues = for (i <- 0 until 100) yield i

    // Build and execute the RheemPlan.
    val sample = rheem
      .loadCollection(inputValues)
      .sample(10)
      .collect()

    // Check the result.
    Assert.assertEquals(10, sample.size)
    Assert.assertEquals(10, sample.toSet.size)
  }

  @Test
  def testDoWhile(): Unit = {
    // Set up RheemContext.
    val rheem = new RheemContext().withPlugin(Java.basicPlugin).withPlugin(Spark.basicPlugin)

    // Generate some test data.
    val inputValues = Array(1, 2)

    // Build and execute a word count RheemPlan.

    val values = rheem
      .loadCollection(inputValues).withName("Load input values")
      .doWhile[Int](vals => vals.max > 100, {
      start =>
        val sum = start.reduce(_ + _).withName("Sum")
        (start.union(sum).withName("Old+new"), sum)
    }).withName("While <= 100")
      .collect().toSet

    val expectedValues = Set(1, 2, 3, 6, 12, 24, 48, 96, 192)
    Assert.assertEquals(expectedValues, values)
  }

  @Test
  def testRepeat(): Unit = {
    // Set up RheemContext.
    val rheem = new RheemContext().withPlugin(Java.basicPlugin).withPlugin(Spark.basicPlugin)

    // Generate some test data.
    val inputValues = Array(1, 2)

    // Build and execute a word count RheemPlan.

    val values = rheem
      .loadCollection(inputValues).withName("Load input values").withName(inputValues.mkString(","))
      .repeat(3,
        _.reduce(_ * _).withName("Multiply")
          .flatMap(v => Seq(v, v + 1)).withName("Duplicate")
      ).withName("Repeat 3x")
      .collect().toSet

    // initial: 1,2 -> 1st: 2,3 -> 2nd: 6,7 => 3rd: 42,43
    val expectedValues = Set(42, 43)
    Assert.assertEquals(expectedValues, values)
  }

  @Test
  def testBroadcast() = {
    // Set up RheemContext.
    val rheem = new RheemContext().withPlugin(Java.basicPlugin).withPlugin(Spark.basicPlugin)
    val builder = new PlanBuilder(rheem)

    // Generate some test data.
    val inputStrings = Array("Hello", "World", "Hi", "Mars")
    val selectors = Array('o', 'l')

    val selectorsDataSet = builder.loadCollection(selectors).withName("Load selectors")

    // Build and execute a word count RheemPlan.
    val values = builder
      .loadCollection(inputStrings).withName("Load input values")
      .filterJava(new ExtendedSerializablePredicate[String] {

        var selectors: Iterable[Char] = _

        override def open(ctx: ExecutionContext): Unit = {
          import scala.collection.JavaConversions._
          selectors = collectionAsScalaIterable(ctx.getBroadcast[Char]("selectors"))
        }

        override def test(t: String): Boolean = selectors.forall(selector => t.contains(selector))

      }).withName("Filter words")
      .withBroadcast(selectorsDataSet, "selectors")
      .collect().toSet

    val expectedValues = Set("Hello", "World")
    Assert.assertEquals(expectedValues, values)
  }

  @Test
  def testGroupBy() = {
    // Set up RheemContext.
    val rheem = new RheemContext().withPlugin(Java.basicPlugin).withPlugin(Spark.basicPlugin)

    val inputValues = Array(1, 2, 3, 4, 5, 7, 8, 9, 10)

    val result = rheem
      .loadCollection(inputValues)
      .groupByKey(_ % 2).withName("group odd and even")
      .map {
        group =>
          import scala.collection.JavaConversions._
          val buffer = group.toBuffer
          buffer.sortBy(identity)
          if (buffer.size % 2 == 0) (buffer(buffer.size / 2 - 1) + buffer(buffer.size / 2)) / 2
          else buffer(buffer.size / 2)
      }.withName("median")
      .collect()

    val expectedValues = Set(5, 6)
    Assert.assertEquals(expectedValues, result.toSet)
  }

  @Test
  def testGroup() = {
    // Set up RheemContext.
    val rheem = new RheemContext().withPlugin(Java.basicPlugin).withPlugin(Spark.basicPlugin)

    val inputValues = Array(1, 2, 3, 4, 5, 7, 8, 9, 10)

    val result = rheem
      .loadCollection(inputValues)
      .group()
      .map {
        group =>
          import scala.collection.JavaConversions._
          val buffer = group.toBuffer
          buffer.sortBy(int => int)
          if (buffer.size % 2 == 0) (buffer(buffer.size / 2) + buffer(buffer.size / 2 + 1)) / 2
          else buffer(buffer.size / 2)
      }
      .collect()

    val expectedValues = Set(5)
    Assert.assertEquals(expectedValues, result.toSet)
  }

  @Test
  def testJoin() = {
    // Set up RheemContext.
    val rheem = new RheemContext().withPlugin(Java.basicPlugin).withPlugin(Spark.basicPlugin)

    val inputValues1 = Array(("Water", 0), ("Tonic", 5), ("Juice", 10))
    val inputValues2 = Array(("Apple juice", "Juice"), ("Tap water", "Water"), ("Orange juice", "Juice"))

    val builder = new PlanBuilder(rheem)
    val dataQuanta1 = builder.loadCollection(inputValues1)
    val dataQuanta2 = builder.loadCollection(inputValues2)
    val result = dataQuanta1
      .join[(String, String), String](_._1, dataQuanta2, _._2)
      .map(joinTuple => (joinTuple.field1._1, joinTuple.field0._2))
      .collect()

    val expectedValues = Set(("Apple juice", 10), ("Tap water", 0), ("Orange juice", 10))
    Assert.assertEquals(expectedValues, result.toSet)
  }

  @Test
  def testJoinAndAssemble() = {
    // Set up RheemContext.
    val rheem = new RheemContext().withPlugin(Java.basicPlugin).withPlugin(Spark.basicPlugin)

    val inputValues1 = Array(("Water", 0), ("Tonic", 5), ("Juice", 10))
    val inputValues2 = Array(("Apple juice", "Juice"), ("Tap water", "Water"), ("Orange juice", "Juice"))

    val builder = new PlanBuilder(rheem)
    val dataQuanta1 = builder.loadCollection(inputValues1)
    val dataQuanta2 = builder.loadCollection(inputValues2)
    val result = dataQuanta1.keyBy(_._1).join(dataQuanta2.keyBy(_._2))
      .assemble((dq1, dq2) => (dq2._1, dq1._2))
      .collect()

    val expectedValues = Set(("Apple juice", 10), ("Tap water", 0), ("Orange juice", 10))
    Assert.assertEquals(expectedValues, result.toSet)
  }

  @Test
  def testIntersect() = {
    // Set up RheemContext.
    val rheem = new RheemContext().withPlugin(Java.basicPlugin).withPlugin(Spark.basicPlugin)

    val inputValues1 = Array(1, 2, 3, 4, 5, 7, 8, 9, 10)
    val inputValues2 = Array(0, 2, 3, 3, 4, 5, 7, 8, 9, 11)

    val builder = new PlanBuilder(rheem)
    val dataQuanta1 = builder.loadCollection(inputValues1)
    val dataQuanta2 = builder.loadCollection(inputValues2)
    val result = dataQuanta1
      .intersect(dataQuanta2)
      .collect()

    val expectedValues = Set(2, 3, 4, 5, 7, 8, 9)
    Assert.assertEquals(expectedValues, result.toSet)
  }

  @Test
  def testPageRank() = {
    // Set up RheemContext.
    val rheem = new RheemContext()
      .withPlugin(Java.graphPlugin)
      .withPlugin(RheemBasics.graphPlugin)
      .withPlugin(Java.basicPlugin)
    import org.qcri.rheem.api.graph._

    val edges = Seq((0, 1), (0, 2), (0, 3), (1, 0), (2, 1), (3, 2), (3, 1)).map(t => Edge(t._1, t._2))

    val pageRanks = rheem
      .loadCollection(edges).withName("Load edges")
      .pageRank(20).withName("PageRank")
      .collect()
      .map(t => t.field0.longValue -> t.field1)
      .toMap

    print(pageRanks)
    // Let's not check absolute numbers but only the relative ordering.
    Assert.assertTrue(pageRanks(1) > pageRanks(0))
    Assert.assertTrue(pageRanks(0) > pageRanks(2))
    Assert.assertTrue(pageRanks(2) > pageRanks(3))
  }

  @Test
  def testMapPartitions() = {
    // Set up RheemContext.
    val rheem = new RheemContext()
      .withPlugin(Java.basicPlugin())
      .withPlugin(Spark.basicPlugin)

    val typeCounts = rheem
      .loadCollection(Seq(0, 1, 2, 3, 4, 6, 8))
        .mapPartitions { ints =>
          var (numOdds, numEvens) = (0, 0)
          ints.foreach(i => if ((i & 1) == 0) numEvens += 1 else numOdds += 1)
          Seq(("odd", numOdds), ("even", numEvens))
        }
      .reduceByKey(_._1, { case ((kind1, count1), (kind2, count2)) => (kind1, count1 + count2) })
      .collect()

    Assert.assertEquals(Set(("odd", 2), ("even", 5)), typeCounts.toSet)
  }

  @Test
  def testZipWithId() = {
    // Set up RheemContext.
    val rheem = new RheemContext().withPlugin(Java.basicPlugin).withPlugin(Spark.basicPlugin)

    val inputValues = for (i <- 0 until 100; j <- 0 until 42) yield i

    val result = rheem
      .loadCollection(inputValues)
      .zipWithId
      .groupByKey(_.field1)
      .map { group =>
        import scala.collection.JavaConversions._
        (group.map(_.field0).toSet.size, 1)
      }
      .reduceByKey(_._1, (t1, t2) => (t1._1, t1._2 + t2._2))
      .collect()

    val expectedValues = Set((42, 100))
    Assert.assertEquals(expectedValues, result.toSet)
  }

  @Test
  def testWriteTextFile() = {
    val tempDir = LocalFileSystem.findTempDir
    val targetUrl = LocalFileSystem.toURL(new File(tempDir, "testWriteTextFile.txt"))

    // Set up RheemContext.
    val rheem = new RheemContext().withPlugin(Java.basicPlugin)

    val inputValues = for (i <- 0 to 5) yield i * 0.333333333333

    val result = rheem
      .loadCollection(inputValues)
      .writeTextFile(targetUrl, formatterUdf = d => f"${d % .2f}")

    val lines = scala.collection.mutable.Set[String]()
    Files.lines(Paths.get(new URI(targetUrl))).forEach(new Consumer[String] {
      override def accept(line: String): Unit = lines += line
    })

    val expectedLines = inputValues.map(v => f"${v % .2f}").toSet
    Assert.assertEquals(expectedLines, lines)
  }

  @Test
  def testSqlOnJava() = {
    // Initialize some test data.
    val configuration = new Configuration
    val sqlite3dbFile = File.createTempFile("rheem-sqlite3", "db")
    sqlite3dbFile.deleteOnExit()
    configuration.setProperty("rheem.sqlite3.jdbc.url", "jdbc:sqlite:" + sqlite3dbFile.getAbsolutePath)

    try {
      val connection: Connection = Sqlite3.platform.createDatabaseDescriptor(configuration).createJdbcConnection
      try {
        val statement: Statement = connection.createStatement
        statement.addBatch("DROP TABLE IF EXISTS customer;")
        statement.addBatch("CREATE TABLE customer (name TEXT, age INT);")
        statement.addBatch("INSERT INTO customer VALUES ('John', 20)")
        statement.addBatch("INSERT INTO customer VALUES ('Timmy', 16)")
        statement.addBatch("INSERT INTO customer VALUES ('Evelyn', 35)")
        statement.executeBatch()
      } finally {
        if (connection != null) connection.close()
      }
    }

    // Set up RheemContext.
    val rheem = new RheemContext(configuration).withPlugin(Java.basicPlugin).withPlugin(Sqlite3.plugin)

    val result = rheem
      .readTable(new Sqlite3TableSource("customer", "name", "age"))
      .filter(r => r.getField(1).asInstanceOf[Integer] >= 18, sqlUdf = "age >= 18").withTargetPlatforms(Java.platform)
      .projectRecords(Seq("name"))
      .map(_.getField(0).asInstanceOf[String])
      .collect()
      .toSet

    val expectedValues = Set("John", "Evelyn")
    Assert.assertEquals(expectedValues, result)
  }

  @Test
  def testSqlOnSqlite3() = {
    // Initialize some test data.
    val configuration = new Configuration
    val sqlite3dbFile = File.createTempFile("rheem-sqlite3", "db")
    sqlite3dbFile.deleteOnExit()
    configuration.setProperty("rheem.sqlite3.jdbc.url", "jdbc:sqlite:" + sqlite3dbFile.getAbsolutePath)

    try {
      val connection: Connection = Sqlite3.platform.createDatabaseDescriptor(configuration).createJdbcConnection
      try {
        val statement: Statement = connection.createStatement
        statement.addBatch("DROP TABLE IF EXISTS customer;")
        statement.addBatch("CREATE TABLE customer (name TEXT, age INT);")
        statement.addBatch("INSERT INTO customer VALUES ('John', 20)")
        statement.addBatch("INSERT INTO customer VALUES ('Timmy', 16)")
        statement.addBatch("INSERT INTO customer VALUES ('Evelyn', 35)")
        statement.executeBatch
      } finally {
        if (connection != null) connection.close()
      }
    }

    // Set up RheemContext.
    val rheem = new RheemContext(configuration).withPlugin(Java.basicPlugin).withPlugin(Sqlite3.plugin)

    val result = rheem
      .readTable(new Sqlite3TableSource("customer", "name", "age"))
      .filter(r => r.getField(1).asInstanceOf[Integer] >= 18, sqlUdf = "age >= 18")
      .projectRecords(Seq("name")).withTargetPlatforms(Sqlite3.platform)
      .map(_.getField(0).asInstanceOf[String])
      .collect()
      .toSet

    val expectedValues = Set("John", "Evelyn")
    Assert.assertEquals(expectedValues, result)
  }
}
