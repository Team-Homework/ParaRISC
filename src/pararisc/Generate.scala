package pararisc

import circt.stage.ChiselStage
import java.nio.file.{Files, Paths}

object Generate {
  def main(args: Array[String]): Unit = {
    var output: Option[String] = None
    var module = "top"
    var smoke = false
    var rest = args.toList
    while (rest.nonEmpty) {
      rest match {
        case "--out" :: directory :: tail => output = Some(directory); rest = tail
        case "--module" :: name :: tail => module = name; rest = tail
        case "--smoke" :: tail => smoke = true; rest = tail
        case _ => throw new IllegalArgumentException(
          "Usage: Generate [--module top|decoder] [--out directory] [--smoke]")
      }
    }
    require(Set("top", "decoder")(module), s"Unknown module: $module")
    require(!smoke || module == "top", "--smoke cannot be combined with --module decoder")
    val topName = if (module == "decoder") "Decoder" else "student_top"
    val defaultOutput = if (module == "decoder") "build/decoder/rtl" else "verilog/generated"
    val directory = Paths.get(output.getOrElse(defaultOutput)).toAbsolutePath.normalize()
    Files.createDirectories(directory)
    ChiselStage.emitSystemVerilogFile(
      if (module == "decoder") new Decoder
      else if (smoke) new EnvironmentSmokeTop
      else new StudentTop,
      Array("--target-dir", directory.toString),
      Array("--disable-all-randomization", "--strip-debug-info",
        "--lowering-options=disallowLocalVariables,disallowPackedArrays")
    )
    Files.writeString(directory.resolve("filelist.f"), s"$topName.sv\n")
    println(s"Generated ${directory.resolve(s"$topName.sv")}")
  }
}
