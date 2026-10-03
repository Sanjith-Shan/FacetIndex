package facetindex.bench

import facetindex.data.FilteredDataset
import facetindex.planner.ConfigModel
import facetindex.planner.CostModelFit
import facetindex.planner.Observation
import facetindex.planner.Planner
import facetindex.strategy.FilterStrategy
import facetindex.strategy.Predicate
import facetindex.strategy.SearchBudget
import facetindex.util.Args
import facetindex.util.DataDir
import facetindex.util.JsonlWriter
import facetindex.util.Machine
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * exp3: the planner against the best single fixed strategy, the FAISS baseline's threshold rule,
 * and an oracle. Models are fitted on public-query measurements and the planner is scored live on
 * the private queries.
 */
object PlannerBench {
    private fun label(file: Path, prefix: String) = file.name.removePrefix(prefix).removeSuffix(".tsv")

    fun run(a: Args): Int {
        val ds = FilteredDataset(a.path("data"), a.str("name", "yfcc-10M"))
        val calibDir = a.path("calib-dir")
        val evalDir = a.pathOrNull("eval-dir")
        val (e, searcher) = Sweep.engine(a, ds)
        val threads = a.int("threads", 4)
        val k = 10
        val target = a.double("target", 0.9)
        val margin = a.double("margin", 0.005)
        val out = JsonlWriter(a.path("out"))
        val include = a.list("configs", "").toSet()

        // Fit one model per configuration from the public per-query files.
        val files = Files.list(calibDir).use { s -> s.filter { it.name.startsWith("public_") && it.name.endsWith(".tsv") }.sorted().toList() }
        val labels = HashMap<String, String>() // file label -> spec label
        val models = ArrayList<ConfigModel>()
        for (f in files) {
            val fileLabel = label(f, "public_")
            val spec = specFromFileLabel(fileLabel)
            if (include.isNotEmpty() && spec !in include) continue
            labels[fileLabel] = spec
            models += CostModelFit.fit(spec, CostModelFit.readTsv(f))
        }
        require(models.isNotEmpty()) { "no public per-query files in $calibDir" }
        Planner.save(models, a.path("model-out", Path.of("results/m3_cost_model.json")))
        val strategies: Map<String, Pair<FilterStrategy, SearchBudget>> = models.associate { m ->
            val spec = StrategySpec.parse(m.config)
            m.config to (strategyFor(e, spec) to spec.budget())
        }

        val pub = ds.queries("public")
        val pubStats = (0 until pub.n).map { e.stats(Predicate(pub.tagsOf(it))) }
        val priv = ds.queries(a.str("queries", "private"))
        val picks = Sweep.picks(a, priv)
        val privStats = picks.associateWith { e.stats(Predicate(priv.tagsOf(it))) }

        val rule = Planner.Rule.valueOf(a.str("rule", "lagrange").uppercase())
        val (knob, predRecall, predCost) = Planner.calibrate(models, rule, pubStats, target + margin)
        println("calibrated ${rule.name.lowercase()} knob=$knob predicted public recall=$predRecall cost=$predCost us")

        // Warm up every candidate configuration once.
        val warm = picks.take(500)
        for ((_, sb) in strategies) for (q in warm) sb.first.search(priv.vector(q), Predicate(priv.tagsOf(q)), k, sb.second)

        val planLog = DataDir.resolve("plan/plan_${a.str("run", "exp3")}.jsonl")
        Files.createDirectories(planLog.parent)
        val knobs = a.doubles("knob-scale", "0.25,0.5,1,2,4").map { knob * it }.let { if (rule == Planner.Rule.THRESHOLD) it.map { v -> v.coerceAtMost(1.0) } else it }.distinct()
        for (kn in knobs) {
            val p = Planner(models, rule, kn)
            val choices = HashMap<Int, Planner.Decision>()
            val overheadNs = HashMap<Int, Long>()
            val loadStart = Machine.load(500)
            val (res, wall) = runBatch(e, priv, picks, threads, k) { q ->
                val t0 = System.nanoTime()
                val pred = Predicate(priv.tagsOf(q))
                val st = e.stats(pred)
                val d = p.choose(st)
                val t1 = System.nanoTime()
                synchronized(choices) { choices[q] = d; overheadNs[q] = t1 - t0 }
                val (s, b) = strategies.getValue(d.config)
                val r = s.search(priv.vector(q), pred, k, b)
                r.rows to (r.scored to d.config)
            }
            val summary = Sweep.summarize(res, wall, privStats, picks.size, threads)
            val mix = res.groupingBy { it.strategy }.eachCount().toSortedMap()
            val ov = overheadNs.values.map { it / 1000.0 }.sorted()
            val row = summary + linkedMapOf(
                "experiment" to "exp3", "planner" to "fitted", "rule" to rule.name.lowercase(), "knob" to kn, "calibrated_knob" to knob,
                "calibrated" to (kn == knob), "target" to target, "margin" to margin, "predicted_public_recall" to predRecall,
                "dataset" to ds.name, "query_set" to priv.name, "candidates" to models.size, "choice_mix" to mix,
                "planner_overhead_us_mean" to ov.average(), "planner_overhead_us_p99" to ov[(ov.size * 0.99).toInt()],
                "qps_equiv_from_latency" to threads * 1e6 / res.map { it.latencyNs / 1000.0 }.average(),
                "threads" to threads, "machine" to Machine.info, "load_start" to loadStart, "load_end" to Machine.load(500), "repeats" to 1,
            )
            out.write(row)
            println("planner knob=$kn recall=${row["recall_at_10"]} qps=${row["qps"]} mix=$mix")
            if (kn == knob) {
                JsonlWriter(planLog).use { w ->
                    JsonlWriter(a.path("plan-sample", Path.of("results/plan.jsonl"))).use { sample ->
                        for ((i, r) in res.withIndex()) {
                            val st = privStats.getValue(r.q); val d = choices.getValue(r.q)
                            val line = linkedMapOf("q" to r.q, "matches" to st.matches, "selectivity" to st.selectivity, "n_tags" to st.nTags,
                                "choice" to d.config, "predicted_cost_us" to d.predictedCostUs, "predicted_recall" to d.predictedRecall,
                                "latency_us" to r.latencyNs / 1000.0, "recall" to r.hits.toDouble() / r.possible, "overhead_us" to overheadNs.getValue(r.q) / 1000.0)
                            w.write(line)
                            if (i % 100 == 0) sample.write(line)
                        }
                    }
                }
            }
        }

        // FAISS baseline's rule, inside this engine: S0 below mt (independence estimate), else IVF.
        val nItems = e.attrs.liveCount().toDouble()
        val faissIvf = a.str("faiss-ivf", "S4:c=16384")
        for (mt in a.doubles("mt", "0.0001,0.0003,0.001,0.003,0.01")) for (np in a.ints("faiss-nprobe", "16,32,64,96")) {
            val ivfSpec = StrategySpec.parse("$faissIvf,nprobe=$np")
            val ivfS = strategyFor(e, ivfSpec); val ivfB = ivfSpec.budget()
            val s0 = strategyFor(e, StrategySpec.parse("S0"))
            val loadStart = Machine.load(500)
            val (res, wall) = runBatch(e, priv, picks, threads, k) { q ->
                val tags = priv.tagsOf(q)
                val est = tags.fold(1.0) { acc, t -> acc * e.attrs.cardinality(t) / nItems }
                val pred = Predicate(tags)
                val r = if (est < mt) s0.search(priv.vector(q), pred, k, SearchBudget()) else ivfS.search(priv.vector(q), pred, k, ivfB)
                r.rows to (r.scored to if (est < mt) "S0" else ivfSpec.label)
            }
            val row = Sweep.summarize(res, wall, privStats, picks.size, threads) + linkedMapOf(
                "experiment" to "exp3", "planner" to "faiss_rule", "mt_threshold" to mt, "ivf" to ivfSpec.label,
                "dataset" to ds.name, "query_set" to priv.name, "choice_mix" to res.groupingBy { it.strategy }.eachCount(),
                "threads" to threads, "machine" to Machine.info, "load_start" to loadStart, "load_end" to Machine.load(500), "repeats" to 1,
            )
            out.write(row)
            println("faiss-rule mt=$mt nprobe=$np recall=${row["recall_at_10"]} qps=${row["qps"]}")
        }

        // Oracle from the private per-query measurements of the same configurations (if provided).
        if (evalDir != null) {
            val perConfig = HashMap<String, Map<Int, Observation>>()
            for ((fileLabel, spec) in labels) {
                val f = evalDir.resolve("private_$fileLabel.tsv")
                if (Files.exists(f)) perConfig[spec] = CostModelFit.readTsv(f).associateBy { it.q }
            }
            val qsCommon = picks.filter { q -> perConfig.values.all { q in it } }
            fun oracle(passFrac: Double): Map<String, Any?> {
                var lat = 0.0; var hits = 0L; var poss = 0L
                val mix = HashMap<String, Int>()
                for (q in qsCommon) {
                    val opts = perConfig.mapValues { it.value.getValue(q) }
                    val pass = opts.filter { it.value.hits >= Math.ceil(passFrac * it.value.possible - 1e-9) }
                    val pick = (if (pass.isNotEmpty()) pass else opts).minBy { it.value.latencyUs }
                    lat += pick.value.latencyUs; hits += pick.value.hits; poss += pick.value.possible
                    mix.merge(pick.key.substringBefore(':'), 1, Int::plus)
                }
                return linkedMapOf("pass_fraction" to passFrac, "queries" to qsCommon.size, "recall_at_10" to hits.toDouble() / poss,
                    "qps_equiv_from_latency" to threads * 1e6 / (lat / qsCommon.size), "strategy_mix" to mix.toSortedMap())
            }
            for (pf in listOf(0.9, 0.8, 1.0)) {
                val row = oracle(pf) + linkedMapOf("experiment" to "exp3", "planner" to "oracle", "configs" to perConfig.size,
                    "note" to "per query, the cheapest configuration (measured latency in the private sweep) whose hits reach pass_fraction x possible",
                    "dataset" to ds.name, "query_set" to priv.name, "threads" to threads, "machine" to Machine.info, "repeats" to 1)
                out.write(row); println("oracle pass=$pf ${row["recall_at_10"]} ${row["qps_equiv_from_latency"]}")
            }
            // Misroutes of the calibrated planner: its choice vs the oracle's (pass 0.9), judged on the private sweep.
            val p = Planner(models, rule, knob)
            var mis = 0; var costly = 0; var failed = 0
            for (q in qsCommon) {
                val st = privStats.getValue(q)
                val choice = p.choose(st).config
                val opts = perConfig.mapValues { it.value.getValue(q) }
                val pass = opts.filter { it.value.hits >= Math.ceil(0.9 * it.value.possible - 1e-9) }
                val best = (if (pass.isNotEmpty()) pass else opts).minBy { it.value.latencyUs }
                val mine = opts[choice] ?: continue
                if (choice != best.key) mis++
                if (mine.latencyUs > 2 * best.value.latencyUs) costly++
                if (mine.hits < Math.ceil(0.9 * mine.possible - 1e-9)) failed++
            }
            val row = linkedMapOf("experiment" to "exp3", "planner" to "misroutes", "queries" to qsCommon.size,
                "differs_from_oracle" to mis, "more_than_2x_oracle_latency" to costly, "below_0_9_recall_on_query" to failed,
                "dataset" to ds.name, "query_set" to priv.name, "machine" to Machine.info, "repeats" to 1)
            out.write(row); println(row)
        }
        out.close()
        searcher?.close()
        return 0
    }

    /** Per-query files are named from the spec label with unsafe characters replaced by '_'. */
    private fun specFromFileLabel(f: String): String {
        // name_k=v,k=v was written as name_k=v_k=v (':' and ',' -> '_'); rebuild it.
        val name = f.substringBefore('_')
        val rest = f.substringAfter('_', "")
        if (rest.isEmpty()) return name
        return name + ":" + rest.split('_').joinToString(",")
    }
}
