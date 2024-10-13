package formal 

import chisel3._
import chiseltest._
import chiseltest.formal._
import org.scalatest.flatspec.AnyFlatSpec

import zhoushan.Core

class ZhoushanFormalSpec extends AnyFlatSpec with Formal with ChiselScalatestTester {
  behavior of "ZhoushanFormal"
  it should "pass" in {
    println("Begin Verification")
    // verify
    verify(new Core(), Seq(BoundedCheck(12), BtormcEngineAnnotation))
  }
}