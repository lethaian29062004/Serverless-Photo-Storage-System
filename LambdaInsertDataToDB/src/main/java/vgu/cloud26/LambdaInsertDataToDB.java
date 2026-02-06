package vgu.cloud26;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.Properties;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsUtilities;
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest;


public class LambdaInsertDataToDB implements RequestHandler<Map<String, Object>, String> {

    
    private static final String RDS_HOST = "database-1.cc38mew6e9au.us-east-1.rds.amazonaws.com";
    private static final int RDS_PORT = 3306;
    private static final String DB_USER = "cloud26";

    // --- CONNECTION STRING ---
    // Format: jdbc:mysql://[HOST]:[PORT]/[DB_NAME]
    // Java uses this URL to locate the database.
    private static final String JDBC_URL = "jdbc:mysql://" + RDS_HOST + ":" + RDS_PORT + "/Cloud26";

    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        LambdaLogger logger = context.getLogger();

        if (input.containsKey("body") && "warmup".equals(input.get("body"))) {
            logger.log("Ping received. Warming up ...");
            return "Warmed up!";
        }


        try {
            // Get data from the Map passed by the Orchestrator
            String description = (String) input.get("description");
            String key = (String) input.get("key");
            String email = (String) input.get("email"); 

            // Key cannot be missing
            if (key == null || key.isEmpty()) {
                throw new RuntimeException("Missing 'key' in payload");
            }
            // Description can be empty
            if (description == null) description = "";
            if (email == null) email = "unknown";



            /* Load the jdbc driver class into memory to ensures the driver 
               is registered with the DriverManager before use. */
            // Acts as a "Translator" enabling the Java application to communicate with the MySQL database.
            Class.forName("com.mysql.cj.jdbc.Driver");

            // Create a Properties object containing: database username, authentication token (password), and SSL settings.
            // SSL (Secure Sockets Layer) secure the connection between the application (browser) & the database (server).
            // This 'props' object will be passed to DriverManager to establish the connection.
            Properties props = setMySqlConnectionProperties();
            

            // Establish the database connection with "Try-with-resources" syntax.
            // "Try-with-resources" : Automatically closes the Connecion when execution finishes (even if an error occurs).  
            try (Connection conn = DriverManager.getConnection(JDBC_URL, props);

                // Prepare the SQL statement with placeholders (?) to prevent SQL Injection attacks.
                 PreparedStatement ps = conn.prepareStatement("INSERT INTO Photos (Description, S3Key, OwnerEmail) VALUES (?, ?, ?)")) {
                // Replace the first '?' with the description 
                ps.setString(1, description);
                // Replace the second '?' with the S3 key (filename) 
                ps.setString(2, key);
                // Replace the third '?' with the email
                ps.setString(3, email);
                // Run the INSERT command. Returns the number of rows affected (should be 1).
                int rows = ps.executeUpdate();
                
                logger.log("Inserted into DB successfully. Rows affected: " + rows);
                

                return "{\"success\": true, \"rows_inserted\": " + rows + "}";
            }


        } catch (Exception ex) {
            logger.log("DB Insert Error: " + ex.toString());

            throw new RuntimeException("DB Insert Failed: " + ex.getMessage());
        }
    }


    // CONFIGURE CONNECTION PROPERTIES 
    // Packages the necessary settings (User, Password, SSL) into a Properties object.
    private static Properties setMySqlConnectionProperties() throws Exception {
        Properties mysqlProps = new Properties();
        mysqlProps.setProperty("useSSL", "true");
        mysqlProps.setProperty("user", DB_USER);
        // call generateAuthToken() to get a temporary credential valid for 15 minutes.
        mysqlProps.setProperty("password", generateAuthToken());
        return mysqlProps;
    }



    private static String generateAuthToken() throws Exception {
        // RdsUtilities - provide support tools for RDS operations.(in this case, auth token generation)
        RdsUtilities rdsUtilities = RdsUtilities.builder().build();
        return rdsUtilities.generateAuthenticationToken(GenerateAuthenticationTokenRequest.builder()
                .hostname(RDS_HOST)
                .port(RDS_PORT)
                .username(DB_USER)
                .region(Region.US_EAST_1)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build());
    }
}