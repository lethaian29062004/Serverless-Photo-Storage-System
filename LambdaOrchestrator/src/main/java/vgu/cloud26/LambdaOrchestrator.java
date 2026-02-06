package vgu.cloud26;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.json.JSONException;
import org.json.JSONObject;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

/**
Workflow
CLIENT:
User selects a file & clicks "Upload" -> Browser reads the file as Base64.
Browser sends an HTTP POST request to this Lambda URL.
Payload: { "key": "img.jpg", "description": "...", "content": "base64...", "email": "...", "token": "..." }

SERVER:
1. Receive JSON payload from Frontend. Call handleRequest() to extract necessary fields.

2. Authentication Check (isValidUser):
   - Call getSecretKeyFromSSM() to get the secret calue.
   - Call generateSecureToken() with the input email & secret key.
   - Compare input token vs expected token..

3. If Auth passed, initialize LambdaClient to invoke Lambda Workers 

4. Return results to Frontend.
*/

// For the lambda function can receive a HTTP request and return a HTTP response
public class LambdaOrchestrator implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    // Define the names of the Worker Lambdas
    private static final String DB_FUNCTION_NAME = "LambdaInsertDataToDB"; 
    private static final String UPLOAD_FUNCTION_NAME = "LambdaUploadObject"; 
    private static final String RESIZER_FUNCTION_NAME = "LambdaResizer"; 
    
    private static final String BUCKET_ORIGINAL = "ann-webapp-bucket"; 
    private static final Region AWS_REGION = Region.US_EAST_1;

    @Override
    // Handle the request sent to LambdaOrchestrator
    // Context : RAM, runtime, logger
    // event includes all HTTP request data sent from Frontend via API Gateway ( chứa gói payload chứa thông tin file)
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {

        /*
        Check if the request is a "keep-alive" ping from AWS EventBridge.
        If the body contains "warmup", return immediately to keep the JVM initialized 
        without executing the heavy database logic or incurring extra costs.
        */
        if (event.getBody() != null && event.getBody().contains("warmup")) {
            context.getLogger().log("Ping received. Warming up...");
            return new APIGatewayProxyResponseEvent().withStatusCode(200).withBody("Warmed up!");
        }

        // Save logs to CloudWatch 
        LambdaLogger logger = context.getLogger();
       
        // The final result of all activities
        JSONObject result = new JSONObject();
        // Results of each activity 
        JSONObject act1 = new JSONObject(); 
        JSONObject act2 = new JSONObject();
        JSONObject act3 = new JSONObject(); 

        try {
            // event.getBody() : Lấy gói payload thô (raw) từ Frontend gửi đến
            // Convert the raw JSON string received from Frontend to usable JSON Object 
            JSONObject bodyJson = new JSONObject(event.getBody());
            // Extract necessary fields 
            String key = bodyJson.getString("key"); // mandatory
            String description = bodyJson.optString("description", ""); // optional
            String contentBase64 = bodyJson.getString("content"); // mandatory
            // Extract email & token for security check
            String email = bodyJson.optString("email", "");
            String token = bodyJson.optString("token", "");
            
            if (!isValidUser(email, token, logger)) {
                return buildResponse(new JSONObject().put("error", "Unauthorized: Invalid or missing token"), 401);
            }


            // Lambda Client : used to invoke other Lambda functions
            // Initialize Lambda Client once for all steps
            // Auto-Close: The 'try(...)' syntax ensures the connection is automatically closed 
            //      after the block finishes, preventing resource leaks.
            try (LambdaClient lambdaClient = LambdaClient.builder().region(AWS_REGION).build()) {



                // =================================================================================
                // ACTIVITY 1: DELEGATE INSERT TASK TO "LambdaInsertDataToDB"
                // =================================================================================
                try {
                    // Prepare Payload 
                    JSONObject dbPayload = new JSONObject();
                    dbPayload.put("key", key);
                    dbPayload.put("description", description);
                    dbPayload.put("email", email); 
                    
                    /* Build Invoke Request
                    SdkBytes : pack the payload into bytes for safety transmission
                    invocationType: Synchronous. Orchestrator just continues the task after receiving the response
                    */ 
                    InvokeRequest dbRequest = InvokeRequest.builder()
                            .functionName(DB_FUNCTION_NAME)
                            .payload(SdkBytes.fromUtf8String(dbPayload.toString()))
                            .invocationType("RequestResponse")
                            .build();

                    // lambdaClient.invoke() - send the request to the target Lambda function  
                    // InvokeResponse : receive the response from the target Lambda function  
                    InvokeResponse dbResponse = lambdaClient.invoke(dbRequest);

                    // Read the response as string (original in bytes)
                    String dbRespStr = dbResponse.payload().asUtf8String();

                    if (dbResponse.statusCode() >= 200 && dbResponse.statusCode() < 300) {
                        act1.put("success", true);
                        act1.put("message", "Delegated DB Insert to " + DB_FUNCTION_NAME + ". Response: " + dbRespStr);
                    } else {
                        throw new RuntimeException("DB Worker failed with status: " + dbResponse.statusCode());
                    }
                } catch (Exception ex) {
                    logger.log("DB delegation error: " + ex.toString());
                    act1.put("success", false);
                    act1.put("message", "DB delegation error: " + ex.toString());
                // not contain 'return' or System.exit() here, for the orchestrator can continue to next activities        
                }



                // =================================================================================
                // ACTIVITY 2: DELEGATE UPLOAD TO "LambdaUploadObject" (the logic is same as Activity 1)
                // =================================================================================
                try {
                    JSONObject uploadPayload = new JSONObject();
                    uploadPayload.put("key", key);
                    uploadPayload.put("content", contentBase64);

                    InvokeRequest uploadRequest = InvokeRequest.builder()
                            .functionName(UPLOAD_FUNCTION_NAME)
                            .payload(SdkBytes.fromUtf8String(uploadPayload.toString()))
                            .invocationType("RequestResponse")
                            .build();

                    InvokeResponse uploadResponse = lambdaClient.invoke(uploadRequest);
                    String uploadRespStr = uploadResponse.payload().asUtf8String();

                    if (uploadResponse.statusCode() >= 200 && uploadResponse.statusCode() < 300) {
                        act2.put("success", true);
                        act2.put("message", "Delegated upload to " + UPLOAD_FUNCTION_NAME + ". Response: " + uploadRespStr);
                    } else {
                        act2.put("success", false);
                        act2.put("message", "Upload Worker failed. Status: " + uploadResponse.statusCode());
                    }
                } catch (Exception ex) {
                    logger.log("Upload delegation error: " + ex.toString());
                    act2.put("success", false);
                    act2.put("message", "Upload delegation error: " + ex.toString());
                }

                // =================================================================================
                // ACTIVITY 3: DELEGATE RESIZE TO "LambdaResizer"
                // =================================================================================
                try {
                    JSONObject resizePayload = new JSONObject();
                    resizePayload.put("bucket", BUCKET_ORIGINAL);
                    resizePayload.put("key", key);

                    InvokeRequest resizeRequest = InvokeRequest.builder()
                            .functionName(RESIZER_FUNCTION_NAME)
                            .payload(SdkBytes.fromUtf8String(resizePayload.toString()))
                            .invocationType("RequestResponse")
                            .build();

                    InvokeResponse resizeResponse = lambdaClient.invoke(resizeRequest);
                    String resizeRespStr = resizeResponse.payload().asUtf8String();

                    act3.put("success", true);
                    act3.put("message", "Resizer invoked. Response: " + resizeRespStr);
                } catch (Exception ex) {
                    logger.log("Resizer invoke error: " + ex.toString());
                    act3.put("success", false);
                    act3.put("message", "Resizer invoke error: " + ex.toString());
                }
            } // LambdaClient closed here

        } catch (JSONException ex) {
            logger.log("JSON Parse error: " + ex.toString());
            return buildResponse(new JSONObject().put("error", "Invalid JSON format"), 400);
        } catch (Exception ex) {
             logger.log("General error: " + ex.toString());
             return buildResponse(new JSONObject().put("error", ex.toString()), 500);
        }

        // Combine all activity results
        result.put("activity1", act1);
        result.put("activity2", act2);
        result.put("activity3", act3);

        return buildSuccessResponse(result);
    }


    // CREDENTIAL CHECK

    private boolean isValidUser(String email, String token, LambdaLogger logger) {
        try {
            if (email == null || email.isEmpty() || token == null || token.isEmpty()) return false;
            String secretKey = getSecretKeyFromSSM(logger);
            String expectedToken = generateSecureToken(email, secretKey, logger);
            return token.equals(expectedToken);
        } catch (Exception e) {
            logger.log("Security Check Error: " + e.getMessage());
            return false;
        }
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

   





    /// Helper Methods - Build HTTP Responses
    // Why need ? AWS API Gateway requires a specific output format (APIGatewayProxyResponseEvent).
    private APIGatewayProxyResponseEvent buildResponse(JSONObject result, int statusCode) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(statusCode);
        // encode the response body to Base64, this ensures special characters 
        // (like Vietnamese accents) travel safely over HTTP
        String encoded = Base64.getEncoder().encodeToString(result.toString().getBytes());
        response.setBody(encoded);
        response.withIsBase64Encoded(true);
        response.setHeaders(java.util.Collections.singletonMap("Content-Type", "application/json"));
        return response;
    }
    // Success 
    private APIGatewayProxyResponseEvent buildSuccessResponse(JSONObject result) {
        return buildResponse(result, 200);
    }  
   
}