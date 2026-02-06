package vgu.cloud26;


import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
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
User enters email & token, click on 'Login' -> the browser sent the Http POST request 
(with the JSON body containing email and token) to the lambda's function URL (through API Gateway)

SERVER:
Call the function handleRequest(), read the JSON body to get the input email and 'input token'.

Call the function getSecretKeyFromSSM(), send a Http Get request to port 2773 - to get the secret value 
of parameter "cloud26-secret_key" -> SSM reply with a JSON containing the secret value.

Call the function generateSecureToken() with the input email & secret key. 
The Lambda re-calculates the expected token using HMAC-SHA256. 
Then, it compares the 'input token' (from user) with this 'expected token'.

Finally, this lambda create a JSON object (response body) containing the validation result 
(valid: true/false) and sent back to the browser -> the browser reads this to allow or deny access.
*/




public class LambdaTokenChecker implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {


    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        LambdaLogger logger = context.getLogger();  
        try {
            String body = request.getBody();
            if (body == null) throw new RuntimeException("Empty body");          
            JSONObject json = new JSONObject(body);
            String inputEmail = json.getString("email");
            String inputToken = json.getString("token");

           
           String secretKey = getSecretKeyFromSSM(logger);
            String expectedToken = generateSecureToken(inputEmail, secretKey, logger);

           

            boolean isValid = false;
            if (inputToken != null && expectedToken != null) {
                isValid = inputToken.equals(expectedToken); // returns TRUE if tokens match
            }

            JSONObject responseBody = new JSONObject();
            responseBody.put("valid", isValid);
            
            if (isValid) {
                responseBody.put("message", "Login Successful!");
            } else {
                responseBody.put("message", "Invalid Token or Email!");
            }

            return new APIGatewayProxyResponseEvent().withStatusCode(isValid ? 200 : 401).withBody(responseBody.toString());

        } catch (Exception e) {
            logger.log("Error: " + e.getMessage());
            return new APIGatewayProxyResponseEvent() .withStatusCode(500).withBody("{\"error\": \"" + e.getMessage() + "\"}");
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
            String base64 = Base64.getEncoder().encodeToString(hmacBytes);
            logger.log("Input String: " + data);
            logger.log("Secure Token: " + base64);
            return base64;

        } catch (NoSuchAlgorithmException e) {
            logger.log("HmacSHA256 algorithm not found: " + e.getMessage());
            return null;
        } catch (InvalidKeyException ex) {
            logger.log("InvalidKeyException: " + ex.getMessage());
            return null;
        }
    }
}