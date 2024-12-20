/**************************************************************************************
* Copyright (c) 2021 Li Shi
*
* Zhoushan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*             http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND, EITHER
* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT, MERCHANTABILITY OR
* FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package zhoushan
import rvspeccore.core.RVConfig

trait ZhoushanConfig {
  // MMIO Address Map
  val ClintAddrBase = 0x02000000
  val ClintAddrSize = 0x10000
  // Bus ID
  val InstCacheId = 1
  val DataCacheId = 2
  val InstUncacheId = 3
  val DataUncacheId = 4
  val SqStoreId = 1
  val SqLoadId = 2
  // Constants
  val FetchWidth = 2
  val DecodeWidth = 2
  val IssueWidth = 3
  val CommitWidth = 2
  // Parameters
  val InstBufferSize = 8
  val RobSize = 16
  val IntIssueQueueSize = 8
  val MemIssueQueueSize = 8
  val PrfSize = 64
  val StoreQueueSize = 4
  // Fomal Verification
  val EnableFormal = true
  val FormalConfig = RVConfig(
    XLEN = 64,
    extensions =  "MCZicsr",
    fakeExtensions = "A",
    functions = Seq("Privileged")
  )
  // Settings
  val TargetOscpuSoc = false
  val EnableDifftest = !TargetOscpuSoc && !EnableFormal
  val EnableMisRateCounter = false
  val EnableQueueAnalyzer = false
  val ResetPc = if (TargetOscpuSoc) "h30000000" else "h80000000"
  val OscpuId = "000000"
  // Debug Info
  val DebugInstBuffer = false
  val DebugRename = false
  val DebugRenameVerbose = false
  val DebugIntIssueQueue = false
  val DebugMemIssueQueue = false
  val DebugCommit = true
  val DebugCommitSimple = false
  val DebugJmpPacket = false
  val DebugSram = false
  val DebugLsu = false
  val DebugStoreQueue = false
  val DebugICache = false
  val DebugDCache = false
  val DebugUncache = false
  val DebugBranchPredictorRas = false
  val DebugBranchPredictorBtb = false
  val DebugArchEvent = false
  val DebugClint = false
  val DebugCrossbar1to2 = false
  val DebugCoreBus = false
}

object ZhoushanConfig extends ZhoushanConfig { }
