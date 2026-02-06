package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Properties;
import org.json.JSONArray;
import org.json.JSONObject;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsUtilities;
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest;

/*
 * WORKFLOW:
 * 1. Frontend sends a GET request to retrieve the list of photos.
 * 2. Lambda connects to the RDS MySQL database using IAM Authentication.
 * 3. Executes the SQL query "SELECT * FROM Photos".
 * 4. Converts the result set into a JSON Array.
 * 5. Returns the JSON Array to the Frontend for rendering.
 */

public class LambdaGetPhotosDB implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final String RDS_INSTANCE_HOSTNAME = "database-1.cc38mew6e9au.us-east-1.rds.amazonaws.com";
    private static final int RDS_INSTANCE_PORT = 3306;
    private static final String DB_USER = "cloud26";
    private static final String JDBC_URL = "jdbc:mysql://" + RDS_INSTANCE_HOSTNAME + ":" + RDS_INSTANCE_PORT + "/Cloud26";

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {

    
        /*
        Check if the request is a "keep-alive" ping from AWS EventBridge.
        If the body contains "warmup", return immediately to keep the JVM initialized 
        without executing the heavy database logic or incurring extra costs.
        */
        if (request.getBody() != null && request.getBody().contains("warmup")) {
            context.getLogger().log("Ping received. Warming up...");
            return new APIGatewayProxyResponseEvent().withStatusCode(200).withBody("Warmed up!");
        }



        LambdaLogger logger = context.getLogger();
        JSONArray items = new JSONArray();

        try {
            /* Load the jdbc driver class into memory to ensures the driver 
               is registered with the DriverManager before use. */
            // Acts as a "Translator" enabling the Java application to communicate with the MySQL database.
            Class.forName("com.mysql.cj.jdbc.Driver");

            
            // Establish the database connection with "Try-with-resources" syntax.
            // "Try-with-resources" : Automatically closes the Connecion when execution finishes (even if an error occurs).
            try (Connection mySQLClient = DriverManager.getConnection(JDBC_URL, setMySqlConnectionProperties());
                 // Prepare the SQL statement with placeholders (?) to prevent SQL Injection attacks.
                 PreparedStatement st = mySQLClient.prepareStatement("SELECT * FROM Photos");
                 ResultSet rs = st.executeQuery()) {

                while (rs.next()) {
                    JSONObject item = new JSONObject();
                    item.put("ID", rs.getInt("ID"));
                    item.put("Description", rs.getString("Description"));
                    item.put("S3Key", rs.getString("S3Key"));
                    item.put("OwnerEmail", rs.getString("OwnerEmail"));
                    items.put(item);
                }
            } 
     
        } catch (ClassNotFoundException ex) {
            logger.log(ex.toString());
        } catch (Exception ex) {
            logger.log(ex.toString());
        }


        // Prepare the response to be returned through the API Gateway to the Frontend.
        String Result = items.toString();
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(200);
        response.setBody(Result);
        response.withIsBase64Encoded(false);

        response.setHeaders(java.util.Collections
                .singletonMap("Content-Type", "application/json"));
        return response;
    }

    
    // CONFIGURE CONNECTION PROPERTIES 
    // Packages the necessary settings (User, Password, SSL) into a Properties object.
    private static Properties setMySqlConnectionProperties() throws Exception {
        Properties mysqlConnectionProperties = new Properties();
        mysqlConnectionProperties.setProperty("useSSL", "true");
        mysqlConnectionProperties.setProperty("user", DB_USER);
        mysqlConnectionProperties.setProperty("password", generateAuthToken());
        return mysqlConnectionProperties;
    }


    // RdsUtilities - provide support tools for RDS operations.(in this case, auth token generation)
    private static String generateAuthToken() throws Exception {      
        RdsUtilities rdsUtilities = RdsUtilities.builder().build();
        String authToken
                = rdsUtilities.generateAuthenticationToken(
                        GenerateAuthenticationTokenRequest.builder()
                                .hostname(RDS_INSTANCE_HOSTNAME)
                                .port(RDS_INSTANCE_PORT)
                                .username(DB_USER)
                                .region(Region.US_EAST_1)
                                .credentialsProvider(DefaultCredentialsProvider.create())
                                .build());
        return authToken;
    }

}