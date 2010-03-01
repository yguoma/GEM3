package edu.mit.csail.cgs.datasets.motifs;

import java.io.*;
import java.util.*;
import java.util.regex.*;
import java.sql.*;
import java.text.ParseException;
import edu.mit.csail.cgs.utils.database.DatabaseFactory;
import edu.mit.csail.cgs.utils.database.DatabaseException;
import edu.mit.csail.cgs.utils.database.UnknownRoleException;
import edu.mit.csail.cgs.utils.io.parsing.PWMParser;
import edu.mit.csail.cgs.utils.*;
import edu.mit.csail.cgs.datasets.*;
import edu.mit.csail.cgs.datasets.species.Organism;

/** Imports a weight matrix from a TAMO formatted file.
 *  Only reads the first WM in the file. 
 *
 * Usage:
 * java edu.mit.csail.cgs.datasets.motifs.WeightMatrixImport --species "Saccharomyces cerevisiae" --wmname HSF1 --wmversion MacIsaac06 --wmtype TAMO --wmfile v1.HSF1.wm
*/

public class WeightMatrixImport {

  private static int MAX_MOTIF_LEN = 200;
  
    public static void main(String args[]) {
        String species = null;
        String wmname = null, wmversion = null, wmtype = null;
        String wmfile = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--species")) { 
                species = args[++i];
                if (species.indexOf(';') != -1) {
                    String[] pieces = species.split(";");
                    species = pieces[0];
                }
            }
            if (args[i].equals("--wmname")) {
                wmname = args[++i];
                if (wmname.indexOf(';') != -1) {
                    String[] pieces = wmname.split(";");
                    wmname = pieces[0];
                    wmversion = pieces[1];
                    if (pieces.length >= 3) {
                        wmtype = pieces[2];
                    }
                }
            }
            if (args[i].equals("--wmversion")) {
                wmversion = args[++i];
            }
            if (args[i].equals("--wmtype")) {
                wmtype = args[++i];
            }
            if (args[i].equals("--") ||
                args[i].equals("--wmfile")) {
                wmfile = args[++i];
            }
        }

        //        if (species == null) {
        //            System.err.println("Must supply a --species"); System.exit(1);
        //        }
        if (wmfile == null) {
            System.err.println("Must supply a --wmfile"); System.exit(1);
        } 
        try {
            if(wmname==null) { 
                insertMultiWMFromFile(species,wmtype,wmfile, wmversion);
            } else { 
                if (wmversion == null) {
                    System.err.println("Must supply a --wmversion"); System.exit(1);
                }
                insertWMFromFile(species,wmname,wmversion,wmtype,wmfile);
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        } catch (NotFoundException ex) {
            ex.printStackTrace();
            System.err.println("Must supply a valid species and genome");
        } catch (UnknownRoleException ex ){
            ex.printStackTrace();
            System.err.println("Couldn't connect to role annotations");
        } catch (FileNotFoundException ex) {
            ex.printStackTrace();
            System.err.println("Couldn't find the input file");
        } catch (ParseException ex) {
            ex.printStackTrace();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }
    
    public static int insertMatrixIntoDB(WeightMatrix matrix) 
        throws SQLException, NotFoundException {
        
        java.sql.Connection cxn =DatabaseFactory.getConnection("annotations");
        int wmid = -1;
        PreparedStatement exists = cxn.prepareStatement("select id from weightmatrix where species = ? and name = ? and version = ? and type = ?");
        exists.setInt(1,matrix.speciesid);
        exists.setString(2,matrix.name);
        exists.setString(3,matrix.version);
        exists.setString(4,matrix.type);
        ResultSet rs = exists.executeQuery();
        if (!rs.next()) {
            rs.close();
            PreparedStatement insertwm;
            if ((matrix.bgModel != null) && matrix.bgModel.hasDBID()) {
            	insertwm = cxn.prepareStatement("insert into weightmatrix(id,species,name,version,type,bg_model_id) values (weightmatrix_id.nextval,?,?,?,?,?)");
            	insertwm.setInt(5, matrix.bgModel.getDBID());
            }
            else {
            	insertwm = cxn.prepareStatement("insert into weightmatrix(id,species,name,version,type) values (weightmatrix_id.nextval,?,?,?,?)");
            }
            insertwm.setInt(1,matrix.speciesid);
            insertwm.setString(2,matrix.name);
            insertwm.setString(3,matrix.version);
            insertwm.setString(4,matrix.type);
            
            //            System.err.println(String.format("Inserting %s %s %s", matrix.name, matrix.version, matrix.type));
            insertwm.execute();
            insertwm.close();
            PreparedStatement getId = cxn.prepareStatement("select weightmatrix_id.currval from dual");
            rs = getId.executeQuery();
            if (rs.next()) {
                wmid = rs.getInt(1);                
            } else {
                System.err.println("No weight matrix id");
                System.exit(1);
            }            
            rs.close();
            getId.close();
        } else {
            wmid = rs.getInt(1);
            PreparedStatement deleteold = cxn.prepareStatement("delete from weightmatrixcols where weightmatrix = ?");
            deleteold.setInt(1,wmid);
            deleteold.execute();
            deleteold.close();
        }
        rs.close();
        exists.close();
           
            
        PreparedStatement insertcol = cxn.prepareStatement("insert into weightmatrixcols(weightmatrix,position,letter,weight) values(?,?,?,?)");
        insertcol.setInt(1,wmid);
        for (int col = 0; col < matrix.length(); col++) {
//             System.err.println(String.format("  Column %d %f %f %f %f", col,
//                                              matrix.matrix[col]['A'],
//                                              matrix.matrix[col]['C'],
//                                              matrix.matrix[col]['T'],
//                                              matrix.matrix[col]['G']));
            insertcol.setInt(2,col);

            insertcol.setString(3,"A");
            insertcol.setFloat(4,matrix.matrix[col]['A']);
            insertcol.execute();
                
            insertcol.setString(3,"C");
            insertcol.setFloat(4,matrix.matrix[col]['C']);
            insertcol.execute();

            insertcol.setString(3,"T");
            insertcol.setFloat(4,matrix.matrix[col]['T']);
            insertcol.execute();

            insertcol.setString(3,"G");
            insertcol.setFloat(4,matrix.matrix[col]['G']);
            insertcol.execute();                
        }           
        insertcol.close();
        DatabaseFactory.freeConnection(cxn);
        return wmid;
    }
    


    public static Set<Integer> insertMultiWMFromFile(String species, String wmtype, String wmfile, String wmversion) 
        throws IOException, SQLException, NotFoundException { 

        HashSet<Integer> ids = new HashSet<Integer>();
        List<WeightMatrix> matrices = null;
        if(wmtype.matches(".*TAMO.*")) { 
            int speciesid = (new Organism(species)).getDBID();
            matrices = PWMParser.readTamoMatrices(wmfile);
            for(WeightMatrix matrix : matrices) { 
                matrix.speciesid = speciesid;
            }
        } else if (wmtype.matches(".*TRANSFAC.*")) {
            matrices = PWMParser.readTRANSFACFreqMatrices(wmfile, wmversion);
        } else if (wmtype.matches(".*JASPAR.*")) {
            matrices = PWMParser.readJASPARFreqMatrices(wmfile, wmversion);
        } else {
            throw new NotFoundException("Unknown weight matrix type " + wmtype);
        }
            for(WeightMatrix matrix : matrices) { 
                ids.add(insertMatrixIntoDB(matrix));                
            }
        return ids;
    }

    /* reads a weight matrix from the specified file,
       inserts it into the database with the specified name and
       version, and returns its dbid.  This means that the file may contain
       only a single weight matrix*/
    public static int insertWMFromFile(String species,
                                       String wmname,
                                       String wmversion,
                                       String wmtype,
                                       String wmfile) throws SQLException, NotFoundException, UnknownRoleException, FileNotFoundException, ParseException, IOException {
        WeightMatrix matrix;
        if (wmtype.matches(".*TAMO.*")) {
            matrix = PWMParser.readTamoMatrix(wmfile);
        } else if (wmtype.matches(".*MEME.*")) {
            matrix = PWMParser.readMemeMatrix(wmfile);
        } else if (wmtype.matches(".*SEQ.*")) { 
            matrix = PWMParser.readAlignedSequenceMatrix(wmfile);
        } else if (wmtype.matches(".*TRANSFAC.*")) {
          //TODO add a method to read a single transfac matrix
          matrix = PWMParser.readTRANSFACFreqMatrices(wmfile, wmversion).get(0);
        } else if (wmtype.matches(".*PRIORITY.*")) {
          matrix = PWMParser.parsePriorityBestOutput(wmfile);
        }
        else {
            System.err.println("Didn't see a program I recognize in the type.  defaulting to reading TAMO format");
            matrix = PWMParser.readTamoMatrix(wmfile);
        }
        matrix.name = wmname;
        matrix.version = wmversion;
        matrix.type = wmtype;
        matrix.speciesid = (new Organism(species)).getDBID();
        return insertMatrixIntoDB(matrix);
    }
    
    
    /* reads a weight matrix from the specified file,
    inserts it into the database with the specified name and
    version, and returns its dbid.  This means that the file may contain
    only a single weight matrix*/
  public static int insertWMFromFile(String species, String wmname, String wmversion, String wmtype, String wmfile, String bgFreq) 
  throws SQLException, NotFoundException, UnknownRoleException, FileNotFoundException, ParseException, IOException {
    WeightMatrix matrix;
    if (wmtype.matches(".*TAMO.*")) {
      matrix = PWMParser.readTamoMatrix(wmfile);
    } else if (wmtype.matches(".*MEME.*")) {
      matrix = PWMParser.readMemeMatrix(wmfile);
    } else if (wmtype.matches(".*SEQ.*")) { 
      matrix = PWMParser.readAlignedSequenceMatrix(wmfile);
    } else if (wmtype.matches(".*TRANSFAC.*")) {
      //TODO add a method to read a single transfac matrix
      matrix = PWMParser.readTRANSFACFreqMatrices(wmfile, wmversion).get(0);
    } else if (wmtype.matches(".*PRIORITY.*")) {
      matrix = PWMParser.parsePriorityBestOutput(wmfile);
    }
    else {
      System.err.println("Didn't see a program I recognize in the type.  defaulting to reading TAMO format");
      matrix = PWMParser.readTamoMatrix(wmfile);
    }
    matrix.name = wmname;
    matrix.version = wmversion;
    matrix.type = wmtype;
    matrix.speciesid = (new Organism(species)).getDBID();
    return insertMatrixIntoDB(matrix);
 }
    /**
     * Constructs a matrix from a set of strings.  The strings must all have the same length.
     * The WeightMatrix returned has the frequencies of the bases at each position given.
     * 
     * @param strings
     * @return
     * @throws IOException
     * @throws ParseException
     */
    public static WeightMatrix buildAlignedSequenceMatrix(Collection<String> strings) throws ParseException {
        WeightMatrix wm = null;
        
        int[] counts = null;
        for(String line : strings) { 
            line = line.trim().toUpperCase();
            if(line.length() > 0) { 
                if(wm == null) { 
                    wm = new WeightMatrix(line.length());
                    counts = new int[line.length()];
                    for(int i = 0; i < wm.length(); i++) {
                        counts[i] = 0;
                        for(int j = 0; j < wm.matrix[i].length; j++) { 
                            wm.matrix[i][j] = (float)0.0;
                        }
                    }
                }
                
                if(line.length() != wm.length()) { 
                    throw new ParseException("Line \"" + line + "\" was of uneven length (" + 
                            wm.length() + ")", 0);
                }
                
                for(int i = 0; i < line.length(); i++) {
                    char c = line.charAt(i);
                    if(c != 'N' && c != '-') { 
                        wm.matrix[i][c] += (float)1.0;
                        counts[i] += 1;
                    }
                }
            } 
        }
        
        for(int i = 0; wm != null && i < wm.length(); i++) { 
            if(counts[i] > 0) { 
                for(int j = 0; j < wm.matrix[i].length; j++) {
                    wm.matrix[i][j] /= (float)counts[i];
                }
            } else { 
                for(int j = 0; j < wm.matrix[i].length; j++) { 
                    wm.matrix[i][j] = (float)1.0 / (float)(wm.matrix[i].length);
                }
            }
        }
        
        return wm;
    }

}

