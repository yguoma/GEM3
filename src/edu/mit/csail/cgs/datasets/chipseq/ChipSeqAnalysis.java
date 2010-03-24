package edu.mit.csail.cgs.datasets.chipseq;

import java.util.*;
import java.io.*;
import java.sql.*;
import edu.mit.csail.cgs.datasets.general.*;
import edu.mit.csail.cgs.datasets.species.Genome;
import edu.mit.csail.cgs.datasets.species.Organism;
import edu.mit.csail.cgs.utils.NotFoundException;
import edu.mit.csail.cgs.utils.database.*;

public class ChipSeqAnalysis {

    private Map<String,String> params;
    private Set<ChipSeqAlignment> foreground, background;
    private String name, version, program;
    private Integer dbid;
    private List<ChipSeqAnalysisResult> results;

    /* these methods are primarily for constructing a ChipSeqAnalysis that you're going
       to store in the database */
    public ChipSeqAnalysis (String name, String version, String program) {
        this.name = name;
        this.version = version;
        this.program = program;
        dbid = null;
        params = null;
        foreground = null;
        background = null;
        results = new ArrayList<ChipSeqAnalysisResult>();
    }
    public void setParameters(Map<String,String> params) {
        this.params = params;
    }
    public void setInputs(Set<ChipSeqAlignment> foreground,
                          Set<ChipSeqAlignment> background) {
        this.foreground = foreground;
        this.background = background;
    }
    public void addResult(ChipSeqAnalysisResult result) {
        results.add(result);
    }

    /* these methods are primarily for querying an object that you've gotten back
       from the database */
    public Integer getDBID() {return dbid;}
    public String getName() {return name;}
    public String getVersion() {return version;}
    public String getProgramName() {return program;}
    public Map<String,String> getParams() {return params;}
    public Set<ChipSeqAlignment> getForeground() {return foreground;}
    public Set<ChipSeqAlignment> getBackground() {return background;}

    /* these methods interact with the database */
    public static ChipSeqAnalysis get(ChipSeqLoader loader, String name, String version) throws NotFoundException, DatabaseException, SQLException {
        java.sql.Connection cxn = DatabaseFactory.getConnection(ChipSeqLoader.role);
        PreparedStatement ps = cxn.prepareStatement("select id, program from chipseqanalysis where name = ? and version = ?");
        ps.setString(1,name);
        ps.setString(2,version);
        ResultSet rs = ps.executeQuery();
        if (!rs.next()) {
            throw new NotFoundException("Couldn't find analysis " + name + "," + version);
        }
        ChipSeqAnalysis result = new ChipSeqAnalysis(name,version, rs.getString(2));
        result.dbid = rs.getInt(1);
        rs.close();
        ps.close();
        ps = cxn.prepareStatement("select name,value from analysisparameters where analysis = ?");
        ps.setInt(1,result.dbid);
        rs = ps.executeQuery();
        HashMap<String,String> params = new HashMap<String,String>();
        while (rs.next()) {
            params.put(rs.getString(1), rs.getString(2));
        }
        result.setParameters(params);
        rs.close();
        ps.close();
        ps = cxn.prepareStatement("select alignment, inputtype from analysisinputs where analysis = ?");
        ps.setInt(1,result.dbid);
        HashSet<ChipSeqAlignment> fg = new HashSet<ChipSeqAlignment>();
        HashSet<ChipSeqAlignment> bg = new HashSet<ChipSeqAlignment>();
        rs = ps.executeQuery();
        while (rs.next()) {
            if (rs.getString(2).equals("foreground")) {
                fg.add(loader.loadAlignment(rs.getInt(1)));
            } else if (rs.getString(2).equals("background")) {
                bg.add(loader.loadAlignment(rs.getInt(1)));
            }
        }
        result.setInputs(fg,bg);
        rs.close();
        ps.close();
		DatabaseFactory.freeConnection(cxn);
        return result;
    }
    private void storeinputs(PreparedStatement ps,
                             String type,
                             int analysis,
                             int alignment) throws SQLException {
        ps.setInt(1,analysis);
        ps.setInt(2,alignment);
        ps.setString(3, type);
        ps.execute();
    }
                             
    public void store() throws SQLException {
        java.sql.Connection cxn = DatabaseFactory.getConnection(ChipSeqLoader.role);
        cxn.setAutoCommit(false);
        String q = "insert into chipseqanalysis (id, name, version, program) values (%s,?,?,?)";
        PreparedStatement ps = cxn.prepareStatement(String.format(q,edu.mit.csail.cgs.utils.database.Sequence.getInsertSQL(cxn, "chipseqanalysis_id")));
        ps.setString(1, name);
        ps.setString(2, version);
        ps.setString(3, program);
        ps.execute();
        ps.close();
        ps = cxn.prepareStatement(edu.mit.csail.cgs.utils.database.Sequence.getInsertSQL(cxn, "chipseqanalysis_id"));
        ResultSet rs = ps.executeQuery();
        rs.next();
        dbid = rs.getInt(1);
        rs.close();
        ps.close();
        
        if (params != null && params.size() > 0) {
            ps = cxn.prepareStatement("insert into analysisparameters(analysis,name,value) values (?,?,?)");
            ps.setInt(1,dbid);
            for (String k : params.keySet()) {
                ps.setString(2,k);
                ps.setString(3,params.get(k));
                ps.execute();
            }
            ps.close();
        }
        ps = null;
        if (foreground != null && foreground.size() > 0) {
            ps = cxn.prepareStatement("insert into analysisinputs(analysis, alignment, type) values (?,?,?)");
            for (ChipSeqAlignment a : foreground) {
                storeinputs(ps, "foreground", dbid, a.getDBID());
            }
        }
        if (background != null && background.size() > 0) {
            if (ps == null) {
                ps = cxn.prepareStatement("insert into analysisinputs(analysis, alignment, type) values (?,?,?)");
            }
            for (ChipSeqAlignment a : background) {
                storeinputs(ps, "background", dbid, a.getDBID());
            }
        }
        if (ps != null) {
            ps.close();
        }
        if (results != null && results.size() > 0) {
            ps = cxn.prepareStatement("insert into analysisresults(analysis,chromosome,startpos,stoppos,position,fgcount,bgcount,strength,peak_shape,pvalue,fold_enrichment) " +
                                     " values (?,?,?,?,?,?,?,?,?,?,?)");
            ps.setInt(1,dbid);
            for (ChipSeqAnalysisResult r : results) {
                ps.setInt(2, r.getGenome().getChromID(r.getChrom()));
                ps.setInt(3, r.getStart());
                ps.setInt(4, r.getEnd());
                if (r.position == null) {
                    ps.setNull(5, java.sql.Types.INTEGER);
                } else {
                    ps.setInt(5, r.position);
                }
                if (r.foregroundReadCount == null) {
                    ps.setNull(6,java.sql.Types.DOUBLE);
                } else {
                    ps.setDouble(6,r.foregroundReadCount);
                }
                if (r.backgroundReadCount == null) {
                    ps.setNull(7,java.sql.Types.DOUBLE);
                } else {
                    ps.setDouble(7,r.backgroundReadCount);
                }
                if (r.strength == null) {
                    ps.setNull(8,java.sql.Types.DOUBLE);
                } else {
                    ps.setDouble(8,r.strength);
                }
                if (r.shape == null) {
                    ps.setNull(9,java.sql.Types.DOUBLE);
                } else {
                    ps.setDouble(9,r.shape);
                }
                if (r.pvalue == null) {
                    ps.setNull(10,java.sql.Types.DOUBLE);
                } else {
                    ps.setDouble(10,r.pvalue);
                }
                if (r.foldEnrichment == null) {
                    ps.setNull(11,java.sql.Types.DOUBLE);
                } else {
                    ps.setDouble(11,r.foldEnrichment);
                }
                ps.execute();
            }
            ps.close();
        }
		DatabaseFactory.freeConnection(cxn);
    }
    public Collection<ChipSeqAnalysisResult> getResults(Genome g) throws SQLException {
        return getResults(g,null);
    }
    public Collection<ChipSeqAnalysisResult> getResults(Region queryRegion) throws SQLException {
        return getResults(queryRegion.getGenome(), queryRegion);
    }
    private Integer isnullint(ResultSet r, int index) throws SQLException {
        Integer i = r.getInt(index);
        if (i == 0 && r.wasNull()) {
            return null;
        } else {
            return i;
        }
    }
    private Double isnulldouble(ResultSet r, int index) throws SQLException {
        Double i = r.getDouble(index);
        if (i == 0 && r.wasNull()) {
            return null;
        } else {
            return i;
        }
    }
    public Collection<ChipSeqAnalysisResult> getResults(Genome genome, Region queryRegion) throws SQLException {
        java.sql.Connection cxn = DatabaseFactory.getConnection(ChipSeqLoader.role);
        String query = "select chromosome, startpos, stoppos, position, fgcount, bgcount, strength, peak_shape, pvalue, fold_enrichment " +
            " from analysisresults where analysis = ? ";
        if (queryRegion != null) {
            query += " and chromosome = ? and startpos >= ? and stoppos <= ?";
        }
        PreparedStatement ps = cxn.prepareStatement(query);
        ps.setInt(1, dbid);
        if (queryRegion != null) {
            ps.setInt(2, queryRegion.getGenome().getChromID(queryRegion.getChrom()));
            ps.setInt(3, queryRegion.getStart());
            ps.setInt(4, queryRegion.getEnd());
        }
        ResultSet rs = ps.executeQuery();
        Collection<ChipSeqAnalysisResult> result = new ArrayList<ChipSeqAnalysisResult>();
        while (rs.next()) {
            ChipSeqAnalysisResult r = new ChipSeqAnalysisResult(genome,
                                                                genome.getChromName(rs.getInt(1)),
                                                                rs.getInt(2),
                                                                rs.getInt(3),
                                                                isnullint(rs,4),
                                                                isnulldouble(rs,5),
                                                                isnulldouble(rs,6),
                                                                isnulldouble(rs,7),
                                                                isnulldouble(rs,8),
                                                                isnulldouble(rs,9),
                                                                isnulldouble(rs,10));
            result.add(r);                                                                
        }
        rs.close();
        ps.close();
		DatabaseFactory.freeConnection(cxn);
        return result;        
    }
        


}