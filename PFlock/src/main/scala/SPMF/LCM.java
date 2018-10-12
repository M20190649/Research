package SPMF;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public class LCM {
    private ArrayList<List<Integer>> readFile(String input) throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader(input));
        String line;

        ArrayList<List<Integer>> transactionsSet = new ArrayList<>();
        while (((line = reader.readLine()) != null)) {
            // split the line into items
            String[] lineSplited = line.split(" ");
            // for each item
            List<Integer> transaction = new ArrayList<>();
            for (String itemString : lineSplited) {
                // increase the support count of the item
                Integer item = Integer.parseInt(itemString);
                transaction.add(item);
            }
            transactionsSet.add(transaction);
        }

        return transactionsSet;
    }

    private ArrayList<String> getSortedPatternsAsStrings(ArrayList<ArrayList<Integer>> maximals){
        ArrayList<String> lcmStrings = new ArrayList<>();
        for (ArrayList<Integer> m: maximals) {
            StringBuilder pattern = new StringBuilder();
            for(Integer i: m){
                pattern.append(i).append(" ");
            }
            lcmStrings.add(pattern.toString().trim());
        }
        Collections.sort(lcmStrings);

        return lcmStrings;
    }

    private void savePatterns(ArrayList<String> patterns, String filename) throws FileNotFoundException {
        try (PrintWriter out = new PrintWriter(filename)) {
            for(String pattern: patterns){
                out.println(pattern);
            }
        }

    }

    public static void main(String[] arg) throws IOException {
        int minsup = 1;
        int mu = 1;
        String input  = arg[0];
        String output = arg[1];
        String flag   = "";
        if(arg.length > 2) flag = arg[2];

        LCM runner = new LCM();

        ArrayList<List<Integer>> transactionsSet = runner.readFile(input);
        AlgoLCM lcm = new AlgoLCM();
        Transactions data = new Transactions(transactionsSet);
        Itemsets itemsets = lcm.runAlgorithm(minsup, data);
        ArrayList<ArrayList<Integer>> maximals = itemsets.getItemsets(mu);
        if(flag.equalsIgnoreCase("debug")) lcm.printStats();

        ArrayList<String> patterns = runner.getSortedPatternsAsStrings(maximals);
        if(flag.equalsIgnoreCase("print")){
            for(String pattern: patterns){
                System.out.println(pattern);
            }
        }
        runner.savePatterns(patterns, output);
        if(flag.equalsIgnoreCase("debug")) lcm.getN();
    }
}

