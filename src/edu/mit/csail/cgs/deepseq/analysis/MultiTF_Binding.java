package edu.mit.csail.cgs.deepseq.analysis;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

import edu.mit.csail.cgs.datasets.general.Point;
import edu.mit.csail.cgs.datasets.general.Region;
import edu.mit.csail.cgs.datasets.motifs.WeightMatrix;
import edu.mit.csail.cgs.datasets.motifs.WeightMatrixImport;
import edu.mit.csail.cgs.datasets.species.Genome;
import edu.mit.csail.cgs.datasets.species.Organism;
import edu.mit.csail.cgs.deepseq.utilities.CommonUtils;
import edu.mit.csail.cgs.ewok.verbs.SequenceGenerator;
import edu.mit.csail.cgs.ewok.verbs.chipseq.GPSParser;
import edu.mit.csail.cgs.ewok.verbs.chipseq.GPSPeak;
import edu.mit.csail.cgs.ewok.verbs.motifs.WeightMatrixScorer;
import edu.mit.csail.cgs.tools.utils.Args;
import edu.mit.csail.cgs.utils.NotFoundException;
import edu.mit.csail.cgs.utils.Pair;

public class MultiTF_Binding {
    public static char[] letters = {'A','C','T','G'};

	Genome genome=null;
	ArrayList<String> names = new ArrayList<String>();
	ArrayList<WeightMatrix> pwms = new ArrayList<WeightMatrix>();
	ArrayList<ArrayList<Site>> all_sites = new ArrayList<ArrayList<Site>>();
	double gc = 0.42;//mouse		gc=0.41 for human
	File dir;
	private SequenceGenerator<Region> seqgen;

	// command line option:  Y:\Tools\GPS\runs\ESTF(the folder contains GEM result folders) --species "Mus musculus;mm9"
	public static void main(String[] args) {
		MultiTF_Binding obj = new MultiTF_Binding(args);
		obj.printBindingOffsets();
	}
	
	public MultiTF_Binding(String[] args){
				
	    try {
	    	Pair<Organism, Genome> pair = Args.parseGenome(args);
	    	if(pair==null){
	    	  System.err.println("No genome provided; provide a Gifford lab DB genome name");
	    	  System.exit(1);
	    	}else{
	    		genome = pair.cdr();
	    	}
	    } catch (NotFoundException e) {
	      e.printStackTrace();
	    }
		    
		dir = new File(args[0]);
		File[] children = dir.listFiles();
		for (int i=0;i<children.length;i++){
			File child = children[i];
			if (child.isDirectory())
				names.add(child.getName());
		}
		
		int round = 2;
		gc = Args.parseDouble(args, "gc", gc);
		
		for (int tf=0;tf<names.size();tf++){
			String name = names.get(tf);
			File gpsFile = new File(new File(dir, name), name+"_"+round+"_GPS_significant.txt");
			String filePath = gpsFile.getAbsolutePath();
			try{
				List<GPSPeak> gpsPeaks = GPSParser.parseGPSOutput(filePath, genome);
				ArrayList<Site> sites = new ArrayList<Site>();
				for (GPSPeak p:gpsPeaks){
					Site site = new Site();
					site.tf_id = tf;
					site.bs = (Point)p;
					sites.add(site);
				}
				all_sites.add(sites);
			}
			catch (IOException e){
				System.out.println(name+" does not have valid GPS/GEM event call file.");
				System.exit(1);
			}
			
			File dir2= new File(dir, name);
			final String suffix = name+"_"+round+"_PFM";
			File[] files = dir2.listFiles(new FilenameFilter(){
				public boolean accept(File arg0, String arg1) {
					if (arg1.startsWith(suffix))
						return true;
					else
						return false;
				}
			});
			if (files.length==0){
				System.out.println(name+" does not have a motif PFM file.");
				pwms.add(null);
				continue;
			}
			try{
				List<WeightMatrix> wms = WeightMatrixImport.readTRANSFACFreqMatrices(files[0].getAbsolutePath(), "file");
				if (wms.isEmpty()){
					System.out.println(name+" does not have a valid motif file.");
					pwms.add(null);
					continue;
				}
				WeightMatrix wm = wms.get(0);
				float[][] matrix = wm.matrix;
				// normalize
		        for (int position = 0; position < matrix.length; position++) {
		            double sum = 0;
		            for (int j = 0; j < letters.length; j++) {
		                sum += matrix[position][letters[j]];
		            }
		            for (int j = 0; j < letters.length; j++) {
		                matrix[position][letters[j]] = (float)(matrix[position][letters[j]] / sum);
		            }
		        }
		        // log-odds
		        for (int pos = 0; pos < matrix.length; pos++) {
		            for (int j = 0; j < letters.length; j++) {
		                matrix[pos][letters[j]] = (float)Math.log(Math.max(matrix[pos][letters[j]], .000001) / 
		                		(letters[j]=='G'||letters[j]=='C'?gc/2:(1-gc)/2));
		            }
		        } 
				pwms.add(wm);
			}
			catch (IOException e){
				System.out.println(name+" motif PFM file reading error!!!");
				pwms.add(null);
			}
		}
		
		seqgen = new SequenceGenerator<Region>();
		seqgen.useCache(true);
	}
	
	class Site implements Comparable<Site>{
		int tf_id;
		Point bs;
		int id;
		public int compareTo(Site s) {					// descending score
			return(bs.compareTo(s.bs));
		}
	}

	private void printBindingOffsets(){
		// classify sites by chrom
		TreeMap<String, ArrayList<Site>> chrom2sites = new TreeMap<String, ArrayList<Site>>();
		for (ArrayList<Site> sites:all_sites){
			for (Site s:sites){
				String chr = s.bs.getChrom();
				if (!chrom2sites.containsKey(chr))
					chrom2sites.put(chr, new ArrayList<Site>());
				chrom2sites.get(chr).add(s);
			}
		}
		// sort sites in each chrom
		for (String chr: chrom2sites.keySet()){
			ArrayList<Site> sites = chrom2sites.get(chr);
			Collections.sort(sites);
			for (int i=0;i<sites.size();i++)
				sites.get(i).id = i;
		}
		int range = 250;
		int seqRange = 30;
		for (int i=0;i<names.size();i++){
			ArrayList<float[]> profiles = new ArrayList<float[]>();
			for (int n=0;n<names.size();n++){
				profiles.add(new float[range*2+1]);
			}
			System.out.println(names.get(i));
			ArrayList<Site> sites = all_sites.get(i);
			WeightMatrix wm = pwms.get(i);
			WeightMatrixScorer scorer = new WeightMatrixScorer(wm);
			for (Site s:sites){
				int id = s.id;
				int b = s.bs.getLocation();
				// figure out the direction of TF binding based on sequence motif match
				int direction = 0;
				String seq = seqgen.execute(s.bs.expand(seqRange));
				Pair<Integer, Double> hit = CommonUtils.scanPWMoutwards(seq, wm, scorer, seqRange, wm.getMaxScore()*0.6);
				if (hit.car()!=-999){
					if (hit.car()>=0)
						direction = 1;
					else
						direction = -1;
				}
				// count the nearby binding calls in upstream and downstream direction
				ArrayList<Site> chromSites = chrom2sites.get(s.bs.getChrom());
				for(int p=id;p<chromSites.size();p++){
					Site s2 = chromSites.get(p);
					int offset = s2.bs.getLocation()-b;
					if (offset>range)
						break;
					float[] profile = profiles.get(s2.tf_id);
					switch(direction){
						case 0: profile[offset+range]+=0.5;profile[-offset+range]+=0.5;break;
						case 1: profile[offset+range]++;break;
						case -1: profile[-offset+range]++;break;
					}
				}
				if (s.id==0)
					continue;
				for(int p=id-1;p>=0;p--){
					Site s2 = chromSites.get(p);
					int offset = s2.bs.getLocation()-b;
					if (offset<-range)
						break;
					float[] profile = profiles.get(s2.tf_id);
					switch(direction){
						case 0: profile[offset+range]+=0.5;profile[-offset+range]+=0.5;break;
						case 1: profile[offset+range]++;break;
						case -1: profile[-offset+range]++;break;
					}
				}
			}
			
			// output
			StringBuilder sb = new StringBuilder(names.get(i).substring(3)+"\t");
			for (int n=0;n<names.size();n++){
				sb.append(names.get(n).substring(3)+"\t");
			}
			sb.append("\n");
			for (int p=-range;p<=range;p++){
				sb.append(p).append("\t");
				for (int n=0;n<names.size();n++){
					float[] profile = profiles.get(n);
						sb.append(String.format("%.0f\t", profile[p+range]));
				}
				sb.append("\n");
			}
			CommonUtils.writeFile(new File(dir, names.get(i)+"_profiles.txt").getAbsolutePath(), sb.toString());
		}
	}
}