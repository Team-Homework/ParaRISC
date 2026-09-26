package cpu2026

import circt.stage.ChiselStage

/** Leaf-module export for focused RTL tests; does not alter student_top. */
object GenerateDatapath {
  def main(args: Array[String]): Unit = {
    require(args.length == 2, "Usage: mill chisel.runMain cpu2026.GenerateDatapath <adder|alu|branch|agu> <output-dir>")
    val stageArgs = Array("--target-dir", args(1))
    args(0) match {
      case "adder" => ChiselStage.emitSystemVerilogFile(new RecursiveCLA32, stageArgs)
      case "alu" => ChiselStage.emitSystemVerilogFile(new IntegerAlu, stageArgs)
      case "branch" => ChiselStage.emitSystemVerilogFile(new BranchUnit, stageArgs)
      case "agu" => ChiselStage.emitSystemVerilogFile(new AddressGenerator, stageArgs)
      case other => throw new IllegalArgumentException(s"Unknown module: $other")
    }
  }
}
