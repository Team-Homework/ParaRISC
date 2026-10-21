package pararisc.fetch

import chisel3.util.isPow2

/** Defaults match ParaRisc/single-issue-chisel's single-issue frontend. */
case class FetchConfig(
    icBytes: Int = 4096,
    lineBytes: Int = 64,
    btbEntries: Int = 64,
    historyBits: Int = 1,
    ramReadAhead: Boolean = false,
    cacheHierarchy: Boolean = false,
    rasWriteStages: Int = 1,
    fetchHeadSliceBits: Int = 0,
    cachedFetchHead: Boolean = true,
    cachedFetchInstruction: Boolean = false,
    debug: Boolean = false
) {
  require(isPow2(lineBytes) && lineBytes >= 8)
  require(isPow2(icBytes) && icBytes >= 2 * lineBytes)

  // BTB writes use banks of eight entries and at least one bank-select bit.
  require(isPow2(btbEntries) && btbEntries >= 16 && btbEntries <= 256)
  require(historyBits >= 0 && historyBits <= 2)
  require(rasWriteStages == 0 || rasWriteStages == 1)
  require(Set(0, 16, 32).contains(fetchHeadSliceBits))
  require(!cachedFetchInstruction || cachedFetchHead)
}
