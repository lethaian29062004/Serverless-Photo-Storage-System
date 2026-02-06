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

import org.json.JSONObject;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

/* 
Workflow :
CLIENT :
User enters their email, click on 'Get Token' -> the browser sent the Http POST request 
(with the JSON body) to the lambda's function URL (through API Gateway)

SERVER:
Call the function handleRequest(), read the JSON body to get the email.

Call the function getSecretKeyFromSSM(), send a Http Get request to port 2773 - to get the secret value 
of parameter "cloud26-secret_key" -> SSM reply with a JSON containing the secret value.

Call the function generateSecureToken() with the value is email & secret key (secret value) , using HMAC-SHA256 
to create the token (the same email + secret key will always return a same token). Then. the hash result
is converted to a Base64 string to be safely transfer over network.

Finally, this lambda create a JSON object (response body) with the email + token 
and sent back to the browser through APIGatewayProxyResponseEvent -> the browser then read the JSON body to get the token.

*/


public class LambdaGenerateToken implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        LambdaLogger logger = context.getLogger();  
        try { // HANDLE REQUEST
            String body = request.getBody();
            JSONObject json = new JSONObject(body);
            String email = json.getString("email");


            // RESPONSE
            String secretKey = getSecretKeyFromSSM(logger); 
            String token = generateSecureToken(email, secretKey, logger);
         
             JSONObject responseBody = new JSONObject();
             responseBody.put("email", email);
             responseBody.put("token", token);
             
             return new APIGatewayProxyResponseEvent().withStatusCode(200).withBody(responseBody.toString());

        } catch (Exception e) {  
             return new APIGatewayProxyResponseEvent().withStatusCode(500).withBody(e.getMessage());
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
                // header : X-Aws-Parameters-Secrets-Token to get access to SSM Parameter Store
                .header("X-Aws-Parameters-Secrets-Token", System.getenv("AWS_SESSION_TOKEN"))
                .header("Accept", "application/json") 
                .GET()
                .build();
                
        // Get the reply from SSM        
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        JSONObject jsonResponse = new JSONObject(response.body());
        String secretValue = jsonResponse.getJSONObject("Parameter").getString("Value");
        
        logger.log("Obtained Secret Key from SSM: " + secretValue);
        
        return secretValue;
    }
  
    
    public static String generateSecureToken(String data, String key, LambdaLogger logger) {
        try {
            // MAC - Message Authentication Code
            // Specify the HMAC-SHA256 algorithm
            Mac mac = Mac.getInstance("HmacSHA256");
            // Convert the secret key (String) to bytes & wrap it in in SecretKeySpec (standard key format for Java crypto)
            SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            // Convert the data (email) to bytes & calculate the HMAC signature (raw binary)
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            // Convert the raw binary to a base64 string 
            return Base64.getEncoder().encodeToString(hmacBytes);
        } catch (Exception e) {
            logger.log("Crypto Error: " + e.getMessage());
            return null;
        }
    }
}