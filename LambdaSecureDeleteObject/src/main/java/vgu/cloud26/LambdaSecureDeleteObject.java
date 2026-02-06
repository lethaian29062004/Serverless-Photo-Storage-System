package vgu.cloud26;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.json.JSONObject;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsUtilities;
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/*
WORKFLOW:
CLIENT:
User clicks the "Delete" button for a specific image -> The browser sends an HTTP DELETE request 
(with the JSON body) to this Lambda's function URL (via API Gateway). 
The JSON body contains: { "key": "filename.jpg", "email": "user@mail.com", "token": "abc..." }


SERVER:
1. Call handleRequest() -> Read the JSON body to extract 'key', 'email', and 'token'.


2. Authentication Check (isValidUser):
   - Call getSecretKeyFromSSM() (HTTP GET port 2773) to get the "cloud26-secret_key".
   - Call generateSecureToken() with the input email & secret key using HMAC-SHA256 to re-calculate the expected token.
   - Compare the 'input token' with the 'expected token'


3. Authorization Check (isOwner):
   - Connect to RDS Database using JDBC.
   - Execute SQL: "SELECT OwnerEmail FROM Photos WHERE S3Key = ?".
   - Compare: 'the email from DB' with 'the user email'.


4. Execution (If Authorizarion checks pass): 
   - Delete the thumbnail from the "Resize Bucket" (S3).
   - Delete the original file from the "Main Bucket" (S3).
   - Delete the record from RDS Database ("DELETE FROM Photos...").


5. Response: Returns the result to the Frontend (also go through API Gateway), 
   prompting the Frontend to refresh the list.

 */

public class LambdaSecureDeleteObject implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    // S3 Config
    private static final String BUCKET_NAME = "ann-webapp-bucket"; 
    private static final String THUMBNAIL_BUCKET_NAME = "ann-resize-bucket"; 
    private static final String RESIZED_PREFIX = "resized-"; 
    private static final Region AWS_REGION = Region.US_EAST_1; 
    
    // Database Config
    private static final String RDS_INSTANCE_HOSTNAME = "database-1.cc38mew6e9au.us-east-1.rds.amazonaws.com";
    private static final int RDS_INSTANCE_PORT = 3306;
    private static final String DB_USER = "cloud26";
    private static final String JDBC_URL = "jdbc:mysql://" + RDS_INSTANCE_HOSTNAME + ":" + RDS_INSTANCE_PORT + "/Cloud26";

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        
        // Prepare http headers for the response
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json"); 


        LambdaLogger logger = context.getLogger();
        String key = null;
        String requestBody = request.getBody();     
        // Initialize email and token variables 
        String email = "";
        String token = "";


        // 1. Extract key, email & token from JSON body
        if (requestBody != null && !requestBody.isEmpty()) {
            try {
                JSONObject bodyJSON = new JSONObject(requestBody);
                key = bodyJSON.getString("key");
                email = bodyJSON.optString("email", "");
                token = bodyJSON.optString("token", "");       
            } catch (Exception e) {
                context.getLogger().log("JSON Parsing Error: " + e.getMessage());
            }
        } 
        
        // 2. Check if key is missing
        if (key == null || key.isEmpty()) {
            APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
            response.setStatusCode(400); // Bad Request
            response.setBody("{\"error\": \"Missing object key in request body.\"}");
            response.setHeaders(headers);
            return response;
        }

        // Check Authenticated User
        if (!isValidUser(email, token, logger)) {
            APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
            response.setStatusCode(401);
            response.setBody("{\"error\": \"Unauthorized: Invalid Token\"}");
            response.setHeaders(headers);
            return response;
        }

        // Check Owner's Email
        if (!isOwner(key, email, logger)) {
            APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
            response.setStatusCode(403);
            response.setBody("{\"error\": \"Forbidden: You are not the owner of this file\"}");
            response.setHeaders(headers);
            return response;
        }
        

        // Create a connection to S3
        S3Client s3Client = S3Client.builder()
                                     .region(AWS_REGION)
                                     .build();
        
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();

        // Try to delete both the original and the resized objects
        try {     
            String resizedKey = RESIZED_PREFIX + key;         
            // 1. Delete S3 Objects
            deleteS3Object(s3Client, THUMBNAIL_BUCKET_NAME, resizedKey, context);
            deleteS3Object(s3Client, BUCKET_NAME, key, context);
            context.getLogger().log("Successfully deleted objects from S3: " + key + " and " + resizedKey);

            // 2. Delete file record from RDS database
            deleteFromDatabase(key, context);
            context.getLogger().log("DB Delete Success: " + key);

            // 5. Return success
            response.setStatusCode(200);
            response.setBody("{\"message\": \"Objects deleted successfully from S3 and DB: " + key + "\"}");
            response.setHeaders(headers);
  
        } catch (S3Exception e) {
            context.getLogger().log("S3 Deletion Error: " + e.awsErrorDetails().errorMessage());
            // Return S3 error (e.g., 404 Not Found, 403 Forbidden) 
            response.setStatusCode(e.statusCode()); 
            response.setBody("{\"error\": \"S3 error: " + e.awsErrorDetails().errorMessage() + "\"}");
            response.setHeaders(headers);
            
        } catch (Exception e) {
            context.getLogger().log("General Error: " + e.getMessage());
            response.setStatusCode(500);
            response.setBody("{\"error\": \"Internal server error: " + e.getMessage() + "\"}");
            response.setHeaders(headers);
        }
        
        return response;
    }
    




    private boolean isValidUser(String email, String token, LambdaLogger logger) {
        try {
            if (email == null || email.isEmpty() || token == null || token.isEmpty()) return false;
            String secretKey = getSecretKeyFromSSM(logger);
            String expectedToken = generateSecureToken(email, secretKey, logger);
            return token.equals(expectedToken);
        } catch (Exception e) { return false; }
    }



    private boolean isOwner(String s3Key, String userEmail, LambdaLogger logger) {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            Properties props = setMySqlConnectionProperties();
            try (Connection conn = DriverManager.getConnection(JDBC_URL, props);
                 PreparedStatement ps = conn.prepareStatement("SELECT OwnerEmail FROM Photos WHERE S3Key = ?")) {       
                ps.setString(1, s3Key);
                ResultSet rs = ps.executeQuery();    
                if (rs.next()) {
                    String owner = rs.getString("OwnerEmail");
                    return userEmail.equalsIgnoreCase(owner);
                    }
                }
        } catch (Exception e) {
            logger.log("DB Check Owner Error: " + e.getMessage());
        }
        return false;
    }




    private String getSecretKeyFromSSM(LambdaLogger logger) throws Exception {
        String parameterName = "cloud26-secret_key"; 

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
                
        String url = "http://localhost:2773/systemsmanager/parameters/get/?name=" + parameterName + "&withDecryption=true";
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-Aws-Parameters-Secrets-Token", System.getenv("AWS_SESSION_TOKEN"))
                .header("Accept", "application/json") 
                .GET()
                .build();
                
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        
        // Parse JSON
        JSONObject jsonResponse = new JSONObject(response.body());
        String secretValue = jsonResponse.getJSONObject("Parameter").getString("Value");
        
        logger.log("Obtained Secret Key from SSM: " + secretValue);
        
        return secretValue;
    }
    

    public static String generateSecureToken(String data, String key, LambdaLogger logger) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hmacBytes);
        } catch (Exception e) { return null; }
    }



    
    private void deleteS3Object(S3Client s3Client, String bucketName, String key, Context context) throws S3Exception {
        context.getLogger().log("Attempting to delete key '" + key + "' from bucket: " + bucketName);
        DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder().bucket(bucketName).key(key).build();
        s3Client.deleteObject(deleteRequest);
    }


    private void deleteFromDatabase(String s3Key, Context context) throws Exception {
        /* Load the jdbc driver class into memory to ensures the driver 
            is registered with the DriverManager before use. */
        // Acts as a "Translator" enabling the Java application to communicate with the MySQL database.
        Class.forName("com.mysql.cj.jdbc.Driver");
        Properties props = setMySqlConnectionProperties();

        // Establish the database connection with "Try-with-resources" syntax.
        // "Try-with-resources" : Automatically closes the Connecion when execution finishes (even if an error occurs).
        try (Connection conn = DriverManager.getConnection(JDBC_URL, props);
        // Prepare the SQL statement with placeholders (?) to prevent SQL Injection attacks.
             PreparedStatement ps = conn.prepareStatement("DELETE FROM Photos WHERE S3Key = ?")) {
            
            ps.setString(1, s3Key);
            int rows = ps.executeUpdate();
            
            if(rows > 0) {
                context.getLogger().log("Deleted " + rows + " row(s) from RDS for key: " + s3Key);
            } else {
                context.getLogger().log("No records found in RDS for key: " + s3Key);
            }
        }
    }

    // Used for both deletionFromDatabase and isOwner methods
    private static Properties setMySqlConnectionProperties() throws Exception {
        // Create a Properties object containing: database username, authentication token (password), and SSL settings.
        // SSL (Secure Sockets Layer) secure the connection between the application (browser) & the database (server).
        // This 'props' object will be passed to DriverManager to establish the connection.
        Properties mysqlProps = new Properties();
        mysqlProps.setProperty("useSSL", "true");
        mysqlProps.setProperty("user", DB_USER);
        mysqlProps.setProperty("password", generateAuthToken());
        return mysqlProps;
    }

    // Helper to Generate IAM Auth Token to access RDS
    private static String generateAuthToken() {
        // RdsUtilities - provide support tools for RDS operations.(in this case, auth token generation)
        RdsUtilities rdsUtilities = RdsUtilities.builder().build();
        return rdsUtilities.generateAuthenticationToken(GenerateAuthenticationTokenRequest.builder()
                .hostname(RDS_INSTANCE_HOSTNAME)
                .port(RDS_INSTANCE_PORT)
                .username(DB_USER)
                .region(AWS_REGION)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build());
    }

}