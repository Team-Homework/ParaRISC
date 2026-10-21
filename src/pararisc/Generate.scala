package pararisc

import circt.stage.ChiselStage
import java.nio.file.{Files, Paths}

object Generate {
  def main(args: Array[String]): Unit = {
    var output = "verilog/generated"
    var smoke = false
    var rest = args.toList
    while (rest.nonEmpty) {
      rest match {
        case "--out" :: directory :: tail => output = directory; rest = tail
        case "--smoke" :: tail => smoke = true; rest = tail
        case _ => throw new IllegalArgumentException("Usage: Generate [--out directory] [--smoke]")
      }
    }
    val directory = Paths.get(output).toAbsolutePath.normalize()
    Files.createDirectories(directory)
    ChiselStage.emitSystemVerilogFile(
      if (smoke) new EnvironmentSmokeTop else new StudentTop,
      Array("--target-dir", directory.toString),
      Array("--disable-all-randomization", "--strip-debug-info",
        "--lowering-options=disallowLocalVariables,disallowPackedArrays")
    )
    Files.writeString(directory.resolve("filelist.f"), "student_top.sv\n")
    println(s"Generated ${directory.resolve("student_top.sv")}")
  }
}
