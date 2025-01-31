package io.quarkiverse.renarde.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

import org.apache.http.client.CookieStore;
import org.apache.http.cookie.Cookie;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.quarkiverse.renarde.oidc.test.MockAppleOidc;
import io.quarkiverse.renarde.oidc.test.MockFacebookOidc;
import io.quarkiverse.renarde.oidc.test.MockGithubOidc;
import io.quarkiverse.renarde.oidc.test.MockGoogleOidc;
import io.quarkiverse.renarde.oidc.test.MockMicrosoftOidc;
import io.quarkiverse.renarde.oidc.test.MockSpotifyOidc;
import io.quarkiverse.renarde.oidc.test.MockTwitterOidc;
import io.quarkiverse.renarde.oidc.test.RenardeCookieFilter;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;

@MockSpotifyOidc
@MockFacebookOidc
@MockGoogleOidc
@MockAppleOidc
@MockMicrosoftOidc
@MockTwitterOidc
@MockGithubOidc
@QuarkusTest
public class RenardeOidcTest {

    @TestHTTPResource
    String url;

    private void oidcTest(String provider, String authId, String email, String firstName, String lastName, String userName) {
        String userNameFromView = userName != null ? userName : "";
        RenardeCookieFilter cookieFilter = new RenardeCookieFilter();
        ValidatableResponse response = follow("/_renarde/security/login-" + provider, cookieFilter);
        response.statusCode(200)
                .body(containsString("Message: Welcome from OIDC for tenant " + provider + ", authId: " + authId
                        + ", firstname: " + firstName + ", lastname: " + lastName + ", username: " + userName + ", email: "
                        + email + "\n"
                        + "OIDC Welcome " + userNameFromView));

        Assertions.assertNotNull(findCookie(cookieFilter.getCookieStore(), "q_session_" + provider));

        verifyLoggedInAndLogout(cookieFilter, "q_session_" + provider);

    }

    @Test
    public void githubLoginTest() {
        // this ID is numeric, and we want to keep it that way to test that it works
        oidcTest("github", "1234", "github@example.com", "Foo", "Bar", "GithubUser");
    }

    @Test
    public void twitterLoginTest() {
        // twitter has no email
        oidcTest("twitter", "USERID", null, "Foo", "Bar", "TwitterUser");
    }

    @Test
    public void googleLoginTest() {
        // google has no username
        oidcTest("google", "USERID", "google@example.com", "Foo", "Bar", null);
    }

    @Test
    public void spotifyLoginTest() {
        // spotify has no username
        oidcTest("spotify", "USERID", "spotify@example.com", "Foo", "Bar", null);
    }

    @Test
    public void microsoftLoginTest() {
        // MS does not set the UPN, defaults to the email as preferred username
        oidcTest("microsoft", "USERID", "microsoft@example.com", "Foo", "Bar", "microsoft@example.com");
    }

    @Test
    public void facebookLoginTest() {
        // no user name from facebook
        oidcTest("facebook", "USERID", "facebook@example.com", "Foo", "Bar", null);
    }

    @Test
    public void appleLoginTest() {
        RenardeCookieFilter cookieFilter = new RenardeCookieFilter();
        ValidatableResponse response = follow("/_renarde/security/login-apple", cookieFilter);
        JsonPath json = response.statusCode(200)
                .extract().body().jsonPath();
        String code = json.get("code");
        String state = json.get("state");

        String location = given()
                .when()
                .filter(cookieFilter)
                .formParam("state", state)
                .formParam("code", code)
                // can't follow redirects due to cookies
                .redirects().follow(false)
                // must be precise and not contain an encoding: probably needs fixing in the OIDC side
                .contentType("application/x-www-form-urlencoded")
                .log().ifValidationFails()
                .post("/_renarde/security/oidc-success")
                .then()
                .log().ifValidationFails()
                .statusCode(302)
                .extract().header("Location");
        // now move on to the GET, but make sure we go over http
        ValidatableResponse completeResponse = follow(location.replace("https://", "http://"), cookieFilter)
                .body(containsString("Message: Welcome from OIDC for tenant apple, authId: USERID"
                        + ", firstname: null, lastname: null, username: null, email: apple@example.com\n"
                        + "OIDC Welcome "))
        // no name, username from apple
        ;

        Assertions.assertNotNull(findCookie(cookieFilter.getCookieStore(), "q_session_apple"));

        verifyLoggedInAndLogout(cookieFilter, "q_session_apple");
    }

    private void verifyLoggedInAndLogout(RenardeCookieFilter cookieFilter, String cookieName) {
        // can go to protected page
        given()
                .when()
                .filter(cookieFilter)
                .get("/SecureController/hello")
                .then()
                .statusCode(200);

        // now logout
        given()
                .when()
                .filter(cookieFilter)
                .redirects().follow(false)
                .get("/_renarde/security/logout")
                .then()
                .statusCode(303)
                // go home
                .header("Location", url)
                // clear cookie
                .cookie(cookieName, "");
    }

    private Object findCookie(CookieStore cookieStore, String name) {
        for (Cookie cookie : cookieStore.getCookies()) {
            if (cookie.getName().equals(name)) {
                return cookie;
            }
        }
        return null;
    }

    private ValidatableResponse follow(String uri, RenardeCookieFilter cookieFilter) {
        do {
            // make sure we turn any https into http, because some providers force https
            if (uri.startsWith("https://")) {
                uri = "http" + uri.substring(5);
            }
            ValidatableResponse response = given()
                    .when()
                    .filter(cookieFilter)
                    // mandatory for Location redirects
                    .urlEncodingEnabled(false)
                    .redirects().follow(false)
                    .log().ifValidationFails()
                    .get(uri)
                    .then()
                    .log().ifValidationFails();
            ExtractableResponse<Response> extract = response.extract();
            if (extract.statusCode() == 302
                    || extract.statusCode() == 303) {
                uri = extract.header("Location");
            } else {
                return response;
            }
        } while (true);
    }

}
