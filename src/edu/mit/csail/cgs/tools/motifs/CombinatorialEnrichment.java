package edu.mit.csail.cgs.tools.motifs;

import java.util.*;
import java.io.*;
import java.sql.*;
import java.text.DecimalFormat;
import cern.jet.random.Binomial;
import cern.jet.random.engine.RandomEngine;

import edu.mit.csail.cgs.utils.database.DatabaseFactory;
import edu.mit.csail.cgs.utils.database.DatabaseException;
import edu.mit.csail.cgs.utils.database.UnknownRoleException;
import edu.mit.csail.cgs.utils.io.parsing.FASTAStream;
import edu.mit.csail.cgs.utils.*;
//import edu.mit.csail.cgs.utils.probability.Binomial;
import edu.mit.csail.cgs.datasets.species.Genome;
import edu.mit.csail.cgs.datasets.motifs.*;
import edu.mit.csail.cgs.tools.motifs.*;
import edu.mit.csail.cgs.tools.utils.Args;


public class CombinatorialEnrichment extends CompareEnrichment {

    Map<String, List<List<WMHit>>> fghits, bghits;
    List<CombResult> results;
    DescendingScoreComparator comparator;
    int fgsize, bgsize;

    public CombinatorialEnrichment() {
        super();
        comparator = new DescendingScoreComparator();
        results = new Vector<CombResult>();
    }

    public void parseArgs(String args[]) throws Exception {
        super.parseArgs(args);
    }

    public Map<String,List<List<WMHit>>> doScan(Map<String,char[]> seqs) {
        Map<String,List<List<WMHit>>> output = new HashMap<String,List<List<WMHit>>>();
        Vector<String> v = new Vector<String>();
        v.addAll(seqs.keySet());
        ArrayList<Thread> threadlist = new ArrayList<Thread>();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(new Scanner(v,
                                              seqs,
                                              output));
            t.start();
            threadlist.add(t);
        }
        boolean anyrunning = true;
        while (anyrunning) {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                // swallow it.  Will just loop again
            }
            anyrunning = false;
            for (int i = 0; i < threadlist.size(); i++) {
                if (threadlist.get(i).isAlive()) {
                    anyrunning = true;
                    break;
                }
            }
        }
        return output;
    }

    class Scanner implements Runnable {
        private Vector<String> keys;
        private Map<String,char[]> seqs;
        private Map<String,List<List<WMHit>>> output;
        public Scanner(Vector<String> k, Map<String,char[]> s,Map<String,List<List<WMHit>>> o) {
            keys = k;
            seqs = s;
            output = o;
        }
        public void run() {
            String s = null;
            try {
                while (true) {
                    s = keys.remove(0);
                    ArrayList<List<WMHit>> hitlists = new ArrayList<List<WMHit>>();
                    for (WeightMatrix matrix : matrices) {
                        double maxscore = matrix.getMaxScore();
                        List<WMHit> hits = WeightMatrixScanner.scanSequence(matrix,
                                                                            (float)(maxscore * cutoffpercent),
                                                                            seqs.get(s));
                        Collections.sort(hits, comparator);
                        hitlists.add(hits);
                    }
                    synchronized (output) {
                        output.put(s,hitlists);
                    }                   
                }

            } catch (ArrayIndexOutOfBoundsException e) {
                // nothing.  time to stop the loop
            }
        }
    }

    public void doScans() {
        fghits = doScan(foreground);
        bghits = doScan(background);       
    }

    class Counter implements Runnable {
        private int first, skip;
        public Counter(int f, int s) {
            first = f;
            skip = s;
        }
        public int count(int matrixone,
                         int matrixtwo,
                         double threshone,
                         double threshtwo,
                         Map<String,List<List<WMHit>>> hits) {
            int count = 0;
            for (String s : hits.keySet()) {
                List<List<WMHit>> hitlists = hits.get(s);
                List<WMHit> l = hitlists.get(matrixone);
                if (l.size() > 0 && l.get(0).getScore() >= threshone) {
                    l = hitlists.get(matrixtwo);
                    if (l.size() > 0 && l.get(0).getScore() >=threshtwo ) {
                        count++;
                    }
                }
            }
            return count;
        }

        public void run() {
            for (int i = first; i < matrices.size(); i += skip) {
                WeightMatrix mi = matrices.get(i);
                double maxscorei = mi.getMaxScore();
                for (int j = i + 1; j < matrices.size(); j++) {
                    WeightMatrix mj = matrices.get(j);
                    double maxscorej = mj.getMaxScore();
                    double percenti = cutoffpercent - step;
                    CombResult result = new CombResult();
                    result.matrices.add(mi);
                    result.matrices.add(mj);
                    result.sizeone = fgsize;
                    result.sizetwo = bgsize;
                    boolean quit = false;
                    while (percenti <= 1 && !quit) {
                        percenti += step;
                        double ti = percenti * maxscorei;
                        double percentj = cutoffpercent - step;
                        while (percentj <= 1 && !quit) {
                            percentj += step;
                            double tj = percentj * maxscorej;
                            int fgcount = count(i,j,ti,tj,fghits);
                            double thetaone = ((double)fgcount) / ((double)fgsize);
                            if (fgsize <= 0 || thetaone < minfrac) {
                                quit = true;
                                continue;

                            }
                            int bgcount = count(i,j,ti,tj,bghits);
                            double thetatwo = ((double)bgcount) / ((double)bgsize);
                            if (thetatwo < minfrac ||
                                thetatwo <= 0) {
                                quit = true;
                                continue;
                            }
                            if (thetatwo > maxbackfrac) {
                                continue;
                            }
                            double fc = Math.log(thetaone / thetatwo);
                            if (Math.abs(fc) < Math.abs(result.logfoldchange) ||
                                Math.abs(fc) < Math.abs(Math.log(minfoldchange))) {
                                continue;
                            }

                            binomial.setNandP(fgsize, thetatwo);
                            double pval = 1 - binomial.cdf(fgcount);
                            if (pval <= filtersig && 
                                pval <= result.pval) {
                                result.pval = pval;
                                result.percents.add(percenti);
                                result.percents.add(percentj);
                                result.cutoffs.add(ti);
                                result.cutoffs.add(tj);
                                result.countone = fgcount;
                                result.counttwo = bgcount;
                                result.logfoldchange = fc;
                                result.freqone = thetaone;
                                result.freqtwo = thetatwo;
                            } 
                        }
                    }
                    if (result.pval <= filtersig && 
                        Math.abs(result.logfoldchange) >= Math.abs(Math.log(minfoldchange)) &&
                        (result.freqtwo <= maxbackfrac) && 
                        (result.freqone >= minfrac || result.freqtwo >= minfrac)) {
                        results.add(result);
                    }
                }
            }
        }
    }

    public void lookForPairs() {
        fgsize = fghits.size();
        bgsize = bghits.size();
        ArrayList<Thread> threadlist = new ArrayList<Thread>();
        int chunk = 1 + (matrices.size() / threads);
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(new Counter(i, threads));
            t.start();
            threadlist.add(t);
        }
        boolean anyrunning = true;
        while (anyrunning) {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                // swallow it.  Will just loop again
            }
            anyrunning = false;
            for (int i = 0; i < threadlist.size(); i++) {
                if (threadlist.get(i).isAlive()) {
                    anyrunning = true;
                    break;
                }
            }
        }
        for (CombResult r : results) {
            System.out.println(r.toString());
        }


    }

    public static void main(String args[]) throws Exception {
        CombinatorialEnrichment ce = new CombinatorialEnrichment();
        ce.parseArgs(args);
        System.err.println("Masking and saving");
        ce.maskSequence();
        ce.saveSequences();
        System.err.println("Doing weight matrix scanning");
        ce.doScans();
        System.err.println("Looking for pairs");
        ce.lookForPairs();
    }


}

class CombResult {

    public double pval, logfoldchange, freqone, freqtwo;
    public List<WeightMatrix> matrices;
    public int sizeone, sizetwo, countone, counttwo;
    public List<Double> percents, cutoffs;

    public CombResult() {
        matrices = new ArrayList<WeightMatrix>();
        percents = new ArrayList<Double>();
        cutoffs = new ArrayList<Double>();
        pval = 1.0;
        logfoldchange = 0;
    }

    public String toString() {
        String out = String.format("%2.1f\t%d\t%d\t%.2f\t%d\t%d\t%.2f\t%.4e\t",
                                   logfoldchange,
                                   countone,
                                   sizeone,
                                   freqone,
                                   counttwo,
                                   sizetwo,
                                   freqtwo,
                                   pval);
        for (int i = 0; i < matrices.size(); i++) {
            out += String.format("\t%s\t%s\t%.2f\t%.1f",
                                 matrices.get(i).name,
                                 matrices.get(i).version,
                                 percents.get(i),
                                 cutoffs.get(i));
        }
        return out;
    }
    
}
class DescendingScoreComparator implements Comparator<WMHit> {
    public int compare(WMHit a, WMHit b) {
        return Float.compare(b.score,a.score);
    }
    public boolean equals(Object o) {
        return o instanceof DescendingScoreComparator;
    }
    
}