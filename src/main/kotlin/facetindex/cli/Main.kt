package facetindex.cli

import facetindex.util.Args
import kotlin.system.exitProcess

private val commands: Map<String, Pair<String, (Args) -> Int>> = linkedMapOf(
    "stats" to ("M0 dataset statistics (results/m0_data.jsonl)" to DataCommands::stats),
    "slice" to ("seeded random slice with recomputed filtered ground truth" to DataCommands::slice),
    "verify-gt" to ("check a GT file against S0 on a sample of queries" to DataCommands::verifyGt),
)

fun main(argv: Array<String>) {
    val all = commands + extraCommands
    if (argv.isEmpty() || argv[0] !in all) {
        System.err.println("usage: facetindex <command> [--key value ...]\n")
        for ((k, v) in all) System.err.println("  %-14s %s".format(k, v.first))
        exitProcess(2)
    }
    val code = all.getValue(argv[0]).second(Args(argv.drop(1)))
    System.out.flush()
    exitProcess(code)
}

/** Commands registered by later milestones (kept in one place so `main` stays short). */
internal val extraCommands: Map<String, Pair<String, (Args) -> Int>> by lazy { Registry.commands }
