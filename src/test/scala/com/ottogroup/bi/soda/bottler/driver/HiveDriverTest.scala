package com.ottogroup.bi.soda.bottler.driver

import org.scalatest.Matchers
import org.scalatest.FlatSpec
import com.ottogroup.bi.soda.test.resources.LocalTestResources
import com.ottogroup.bi.soda.test.resources.LocalTestResources
import com.ottogroup.bi.soda.dsl.transformations.sql.HiveTransformation

class HiveDriverTest extends FlatSpec with Matchers {
  var cachedDriver: HiveDriver = null
  def driver: HiveDriver = {
    if (cachedDriver == null)
      cachedDriver = new LocalTestResources().hiveDriver
    cachedDriver
  }

  "HiveDriver" should "be named hive" taggedAs (DriverTests) in {
    driver.name shouldBe "hive"
  }

  it should "execute hive tranformations synchronously" taggedAs (DriverTests) in {
    val driverRunState = driver.runAndWait(HiveTransformation("SHOW TABLES"))

    driverRunState shouldBe a[DriverRunSucceeded[HiveTransformation]]
  }

  it should "execute hive tranformations and return errors when running synchronously" taggedAs (DriverTests) in {
    val driverRunState = driver.runAndWait(HiveTransformation("FAIL ME"))

    driverRunState shouldBe a[DriverRunFailed[HiveTransformation]]
  }

  it should "execute hive tranformations asynchronously" taggedAs (DriverTests) in {
    val driverRunHandle = driver.run(HiveTransformation("SHOW TABLES"))

    var runWasAsynchronous = false

    while (driver.getDriverRunState(driverRunHandle).isInstanceOf[DriverRunOngoing[HiveTransformation]])
      runWasAsynchronous = true

    runWasAsynchronous shouldBe true
    driver.getDriverRunState(driverRunHandle) shouldBe a[DriverRunSucceeded[HiveTransformation]]
  }

  it should "execute hive tranformations and return errors when running asynchronously" taggedAs (DriverTests) in {
    val driverRunHandle = driver.run(HiveTransformation("FAIL ME"))

    var runWasAsynchronous = false

    while (driver.getDriverRunState(driverRunHandle).isInstanceOf[DriverRunOngoing[HiveTransformation]])
      runWasAsynchronous = true

    runWasAsynchronous shouldBe true
    driver.getDriverRunState(driverRunHandle) shouldBe a[DriverRunFailed[HiveTransformation]]
  }
}