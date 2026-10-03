# Bug log

Every bug found while building FacetIndex, in the order found, with the tool or check that caught it.

| # | Milestone | Bug | Found by | Fix |
|---|---|---|---|---|
| 1 | M0 | `AttributeStore.insert` on a row that was already live rewrote the row's tag array but never updated the per-tag bitmaps, so a bitmap could list a row that no longer carried the tag | JUnit model test: the store against a `HashMap` model under 5,000 random insert, delete and `setAttrs` operations | Insert on a live row now goes through `setAttrs(row, new, current)`, which updates both sides |
| 2 | M0 | The two Kotest property tests for the SIMD kernel were written as expression bodies (`= runBlocking { ... }`), so they returned a non-`Unit` value and JUnit 5 silently skipped them | Reading the test report: the tests were missing from the PASSED list | Declared `: Unit` and ended the block with `Unit`; both now run and pass |
| 3 | M2 | k-means++ seeding on 500k points ran for over 15 minutes at under one core: the Kotlin distance loops kept the Vector API species in an instance field, so C2 could not treat it as a constant and the vector code fell back to its slow, allocating path | Watching the build log stall and the process CPU time in Task Manager | Moved the float kernels (`dot`, `f32`) into `L2.java`, where the species is a `static final` constant |
