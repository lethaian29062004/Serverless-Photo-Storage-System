package vgu.cloud26;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.json.JSONObject;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger; 
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsResponse; 
import software.amazon.awssdk.services.s3.model.S3Object; 

/*
WORKFLOW:
CLIENT:
User clicks the "Download" button -> The browser sends an HTTP PUT request to this Lambda's function URL.
The JSON body contains: { "key": "filename.jpg", "email": "user@mail.com", "token": "abc..." }

SERVER:
1. Handle OPTIONS (CORS): 
   If the request is a Preflight Check (OPTIONS method) -> Return 200 OK immediately.

2. Call handleRequest() -> Read the JSON body to extract 'key', 'email', and 'token'.

3. Authentication Check (isValidUser):
   - Call getSecretKeyFromSSM() to get the "cloud26-secret_key".
   - Call generateSecureToken() with the input email & secret key.
   - Compare input token vs expected token

4. Validation: Lambda scans the S3 bucket to verify the file exists and is within the size limit (10MB).

5. Processing (If valid user): 
   - Download the raw binary data (bytes) of the image from S3 into memory.
   - Encode this binary data into a Base64 String (text format) to send safely over JSON.

6. Response: Returns the Base64 String to the Frontend, allowing the browser to display the image securely.andles the response correctly.
*/

public class LambdaGetObject implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {

        // 1. Handle CORS Preflight (OPTIONS request)
        // When the Frontend sends a request with custom headers (like 'Content-Type: application/json'),
        // the browser automatically sends an 'OPTIONS' request to this lambda first to check permissions.
        if ("OPTIONS".equalsIgnoreCase(request.getHttpMethod())) {
            // We must intercept this 'OPTIONS' request and return 200 OK immediately.
            // If not, the code below will try to parse an empty Body, causing a crash or 400 Error.
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withHeaders(java.util.Collections.singletonMap("Access-Control-Allow-Origin", "*"))
                    .withBody("OK");
        }

        String key = null;

        // Get the key, email & token from the JSON body
        try {
            String requestBody = request.getBody();    
            // Check if AWS has encoded the JSON Body (Base64). If so, decode it first.
            if (Boolean.TRUE.equals(request.getIsBase64Encoded()) && requestBody != null) {
                byte[] decodedBytes = Base64.getDecoder().decode(requestBody);
                requestBody = new String(decodedBytes);
            }

            if (requestBody != null && !requestBody.isEmpty()) {
                JSONObject bodyJSON = new JSONObject(requestBody);
                if (bodyJSON.has("key")) {
                    key = bodyJSON.getString("key");
                }
                
                String email = bodyJSON.optString("email", "");
                String token = bodyJSON.optString("token", "");
                if (!isValidUser(email, token, context.getLogger())) {
                    return new APIGatewayProxyResponseEvent()
                            .withStatusCode(401)
                            .withHeaders(java.util.Collections.singletonMap("Access-Control-Allow-Origin", "*"))
                            .withBody("Unauthorized: Invalid Token");
                }
            }
        } catch (Exception e) {      
            context.getLogger().log("JSON Parsing Error: " + e.getMessage());
        }

        // 3. Validate Key
        if (key == null || key.isEmpty()) {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(400)
                    .withBody("Error: Missing 'key' in request body.");
        }

        // 4. Create the connection to S3
        String bucketName = "ann-webapp-bucket";
        S3Client s3Client = S3Client.builder()
                .region(Region.US_EAST_1)
                .build();

        // Retrieve the list of all objects in the bucket to check if our requested 'key' exists.        
        ListObjectsRequest listObjects = ListObjectsRequest
                .builder()
                .bucket(bucketName)
                .build();
        ListObjectsResponse res = s3Client.listObjects(listObjects);
        List<S3Object> objects = res.contents();
        

         // Declare max size image = 10MB
        int maxSize = 10 * 1024 * 1024;
        Boolean found = false;
        Boolean validSize = false;
        // mimeType : image/png , image/jpeg, text/html , application/octet-stream (default mime type)... 
        String mimeType = "application/octet-stream";

        // Loop through every file in the bucket to find the matching key
        for (S3Object object : objects) {
            if (object.key().equals(key)) {
                found = true;
                // Validate size
                long objectSize = object.size(); 
                if (objectSize < maxSize){
                    validSize = true;
                }

                // Determine mimeType based on file extension
                // lastIndexOf('.') finds the index of the last dot 
                int lastDotIndex = key.lastIndexOf('.');
                // ensure the dot is not the first (.gitignore) or last character
                if (lastDotIndex > 0 && lastDotIndex < key.length() - 1) {
                    // Get the extension part AND convert to LowerCase (e.g. JPG -> jpg)
                    String extension = key.substring(lastDotIndex + 1).toLowerCase();
                    
                    switch (extension) {
                        case "png" -> mimeType = "image/png";
                        case "html" -> mimeType = "text/html";
                        case "jpg", "jpeg" -> mimeType = "image/jpeg";
                        default -> { }
                    }
                }
                
                break; // Found the key, exit the loop
            }
        }


        String encodedString ;
        // Only proceed to get the object if it was found and size is valid
        if (found && validSize) {
            GetObjectRequest s3Request
                    = GetObjectRequest.builder()
                            .bucket(bucketName)
                            .key(key)
                            .build();

           // Create a byte array to hold the file data                 
           byte[] buffer ; 

            // s3Client.getObject() - opens a stream to download data from S3.
            // Using "try(...)" ensures the stream is automatically closed after reading to prevent memory leaks.
            try (ResponseInputStream<GetObjectResponse> s3Response
                    = s3Client.getObject(s3Request)) {

                // Read ALL bytes from the S3 stream and store them into the 'buffer' (RAM of the Lambda).   
                buffer = s3Response.readAllBytes();

            } catch (IOException ex) {
                context.getLogger().log("IOException: " + ex);
                return new APIGatewayProxyResponseEvent().withStatusCode(500).withBody("Error reading from S3");
            }        

            // JSON requires text data, but images are binary data.
            // Base64 encoder - convert the byte array (binary) into String.
            // Frontend will decode this String back to an image later.   
            encodedString = Base64.getEncoder().encodeToString(buffer);

        } else {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(404)
                    .withBody("Error: File not found or size exceeds limit.");
        }


        APIGatewayProxyResponseEvent response
                = new APIGatewayProxyResponseEvent();
        response.setStatusCode(200);
        response.setBody(encodedString);
        response.withIsBase64Encoded(true);
        response.setHeaders(java.util.Collections.singletonMap("Content-Type", mimeType));
        return response;
    }




    // CHECK CREDENTIAL
    
    private boolean isValidUser(String email, String token, LambdaLogger logger) {
        try {
            if (email == null || email.isEmpty() || token == null || token.isEmpty()) return false;
            String secretKey = getSecretKeyFromSSM(logger);
            String expectedToken = generateSecureToken(email, secretKey, logger);
            return token.equals(expectedToken);
        } catch (Exception e) { return false; }
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
                .header("Accept", "application/json") // [MỚI] Thêm header này theo thầy
                .GET()
                .build();
                
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        
        
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

}