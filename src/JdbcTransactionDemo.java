//STEP 1. Import required packages
import java.sql.*;
/*************
// Save this in a separate class class Employee.java
public class Employee{
	String first_name;
	String last_name;
	public Employee(String fname, String lname){first_name=fname;last_name=lname;};
	public void setFname(String fname){first_name=fname;}
	public void setLname(String lname){last_name=lname;}
}
*********************/

public class JdbcTransactionDemo {
   //STEP 2a: Set JDBC driver name and database URL
   static final String JDBC_DRIVER = "com.mysql.jdbc.Driver";  
//   static final String DB_URL = "jdbc:mysql://localhost/companydb";
   static final String DB_URL = "jdbc:mysql://localhost/companydb?useSSL=false";

//Modify the following insert statement with YOUR details... use your roll number for SSN
  static final String INSERT_EMP = "Insert into employee(fname,minit,lname,ssn,bdate,address,sex,salary,super_ssn,dno) VALUES ('raj','J', 'gandhi', 'bt2024172','2006-08-29','address','M',100000,null,null)";

   //  Database credentials
   static final String USER = "root";
   static final String PASS = "sql@raj";
   
   public static void main(String[] args) {
   Connection conn = null;
   Statement stmt = null;
   try{
      //STEP 2b: Register JDBC driver
      Class.forName(JDBC_DRIVER);

      //STEP 3: Open a connection
      System.out.println("Connecting to database...");
      conn = DriverManager.getConnection(DB_URL,USER,PASS);
      
      // Set auto commit as false.
      conn.setAutoCommit(false);

      //STEP 4: Insert new employee
      System.out.println("Inserting one row....");
      stmt = conn.createStatement();
      stmt.executeUpdate(INSERT_EMP);  

	  //STEP 5: Commit changes
	  conn.commit();		
      System.out.println("Transaction committed successfully.");

   }catch(SQLException se){
      //Handle errors for JDBC
      se.printStackTrace();
      // If there is an error then rollback the changes.
      System.out.println("Rolling back data here....");
      try{
         if(conn!=null)
             conn.rollback();
      }catch(SQLException se2){
	      System.out.println("Rollback failed....");
              se2.printStackTrace();
      }
   }catch(Exception e){
      //Handle errors for Class.forName
      e.printStackTrace();
   }finally{
      //finally block used to close resources
      try{
         if(stmt!=null)
            stmt.close();
      }catch(SQLException se2){
      }// nothing we can do
      try{
         if(conn!=null)
            conn.close();
      }catch(SQLException se){
         se.printStackTrace();
      }//end finally try
   }//end try
   System.out.println("Goodbye!");
}//end main
}//end FirstExample
