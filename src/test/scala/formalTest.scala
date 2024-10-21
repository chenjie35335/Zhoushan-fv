package formal 

import chisel3._
import chiseltest._
import chiseltest.formal._
import org.scalatest.flatspec.AnyFlatSpec
import zhoushan.{Alu, Core, Execution, Prf}
import zhoushan.Rename
import zhoushan.StallRegister
import zhoushan.Fence
import zhoushan.CacheController
import zhoushan.CacheBusWithUserIO
import zhoushan.Rob
import zhoushan.Meta
import zhoushan.Sram
import zhoushan.BranchPredictor

class ZhoushanFormalSpec extends AnyFlatSpec with Formal with ChiselScalatestTester {
  behavior of "ZhoushanFormal"
  it should "pass" in {
    println("Begin Verification")
    // verify
    verify(new Core(), Seq(BoundedCheck(12), BtormcEngineAnnotation))
  }
}