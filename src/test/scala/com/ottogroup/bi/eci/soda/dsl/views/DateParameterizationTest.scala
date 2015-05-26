package com.ottogroup.bi.eci.soda.dsl.views

import org.scalatest.FlatSpec
import org.scalatest.BeforeAndAfter
import org.scalatest.Matchers
import com.ottogroup.bi.soda.dsl.views.DateParameterizationUtils
import com.ottogroup.bi.soda.dsl.Parameter._

class DailyParameterizationTest extends FlatSpec with BeforeAndAfter with Matchers {
  "prevDay" should "compute the previous date" in {
    val prevDate = DateParameterizationUtils.prevDay(p("2014"), p("01"), p("01")) match {
      case Some((prevYear, prevMonth, prevDay)) => (prevYear.v.get, prevMonth.v.get, prevDay.v.get)
      case None => null
    }

    prevDate shouldEqual ("2013", "12", "31")
  }

  it should "stop at the earliest date" in {
    val prevDate = DateParameterizationUtils.prevDay(p("2013"), p("12"), p("01"))

    prevDate shouldBe None
  }

  "thisAndPrevDays" should "deliver all days from the start date down to the earliest date" in {
    val days = DateParameterizationUtils.thisAndPrevDays(p("2014"), p("01"), p("11"), DateParameterizationUtils.defaultEarliestDay)

    var firstDay: String = null
    var lastDay: String = null

    for (day <- days) {
      if (firstDay == null)
        firstDay = s"${day._1.v.get}${day._2.v.get}${day._3.v.get}"
      lastDay = s"${day._1.v.get}${day._2.v.get}${day._3.v.get}"
    }

    firstDay shouldEqual "20140111"
    lastDay shouldEqual "20131201"
  }

  "thisMonthAndPrevDay" should "count down from the last day of the given month" in {
    DateParameterizationUtils.thisAndPrevDays(p("2014"), p("02"), DateParameterizationUtils.defaultEarliestDay) shouldEqual DateParameterizationUtils.thisAndPrevDays(p("2014"), p("02"), p("28"), DateParameterizationUtils.defaultEarliestDay)
  }

}