package io.github.shrishaanth.axon.eval;

import java.util.Arrays;

/** Entry point for the evaluation harness. Each subcommand writes a raw result file under eval/results/. */
public final class EvalMain {

    private EvalMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: axon-eval <survey|memory|e1a|e1b> ...");
            System.exit(2);
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (args[0]) {
            case "survey" -> ParseSurvey.main(rest);
            case "memory" -> MemoryProbe.main(rest);
            case "e1a" -> MutationExperiment.main(rest);
            case "e1b" -> HistoryAgreement.main(rest);
            case "demo-service" -> io.github.shrishaanth.axon.eval.demo.DemoService.main(rest);
            case "demo-clients" -> io.github.shrishaanth.axon.eval.demo.DemoClients.main(rest);
            default -> {
                System.err.println("unknown command: " + args[0]);
                System.exit(2);
            }
        }
    }
}
