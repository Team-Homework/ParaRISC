package pararisc.fetch

import circt.stage.ChiselStage
import java.nio.file.{Files, Paths}

/** Standalone frontend RTL for integration and protocol regression tests. */
object GenerateFetch {
  def main(args: Array[String]): Unit = {
    require(args.length <= 1, "Usage: GenerateFetch [output-directory]")
    val directory = Paths.get(args.headOption.getOrElse("build/fetch/rtl"))
      .toAbsolutePath.normalize()
    Files.createDirectories(directory)
    ChiselStage.emitSystemVerilogFile(
      new Frontend(FetchConfig(debug = true)),
      Array("--target-dir", directory.toString),
      Array("--disable-all-randomization", "--strip-debug-info",
        "--lowering-options=disallowLocalVariables,disallowPackedArrays")
    )
  }
}
