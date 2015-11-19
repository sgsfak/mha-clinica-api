import com.ning.http.client.*;
import io.undertow.security.idm.Account;
import io.undertow.server.HandlerWrapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.Cookie;
import io.undertow.server.handlers.cache.LRUCache;
import io.undertow.util.AttachmentKey;
import io.undertow.util.StatusCodes;
import net.minidev.json.JSONObject;
import net.minidev.json.parser.JSONParser;
import net.minidev.json.parser.ParseException;

import javax.security.auth.Subject;
import java.security.Principal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by ssfak on 13/11/15.
 */
public class AccessTokenValidator implements HandlerWrapper {

    final String validatorURI;
    final LRUCache<String, Account> cache;
    final AsyncHttpClient httpClient;
    final String sessionName = "JSESSIONID";

    public static AttachmentKey<Account> MHA_ACCOUNT = AttachmentKey.create(Account.class);
    AccessTokenValidator(final String validatorURI) {
        this(validatorURI, 100, 10 * 60 * 1000);
    }

    AccessTokenValidator(final String validatorURI,
                         final int maxTokensToCache, final int maxAgeMilliSeconds) {
        this.validatorURI = validatorURI;
        this.cache = new LRUCache<>(maxTokensToCache, maxAgeMilliSeconds);
        this.httpClient = new AsyncHttpClient(new AsyncHttpClientConfig.Builder()
                .setAllowPoolingConnections(true)
                .setAcceptAnyCertificate(true) // XXX: accepting any certificate for the validator uri.. a bit unsafe?
                .build());
    }

    @Override
    public HttpHandler wrap(final HttpHandler nextHandler) {
        return (HttpServerExchange exchange) -> {
            final Map<String, Cookie> requestCookies = exchange.getRequestCookies();
            // System.out.println(requestCookies);
            if (!requestCookies.containsKey(this.sessionName)) {
                exchange.setStatusCode(StatusCodes.UNAUTHORIZED);
                exchange.getResponseSender().send("You 've been logged out...");
                return;
            }

            String sessionId = requestCookies.get(this.sessionName).getValue();
            final Account account = this.cache.get(sessionId);
            if (account == null) {
                validate_token(sessionId, nextHandler, exchange);
            } else {
                exchange.putAttachment(MHA_ACCOUNT, account);
                nextHandler.handleRequest(exchange);
            }
        };
    }

    private void validate_token(final String sessionId, final HttpHandler nextHandler, final HttpServerExchange exchange) {

        Request r = new RequestBuilder().setUrl(validatorURI)
                .setMethod("GET")
                .setHeader("Cookie", String.format("%s=%s", this.sessionName, sessionId))
                .build();
        this.httpClient.prepareRequest(r).execute(new AsyncCompletionHandler<Void>() {
            @Override
            public Void onCompleted(Response response) throws Exception {
                System.out.println("[TOKEN-VALIDATE] Response returned " + response.getStatusCode() + " for session " + sessionId + " at thread " + Thread.currentThread().getName() + "\n");
                final String responseBody = response.getResponseBody();
                // System.out.println("--> " + responseBody);
                if (response.getStatusCode() != StatusCodes.OK) {
                    exchange.setStatusCode(StatusCodes.UNAUTHORIZED);
                    exchange.getResponseSender().send("You are not authorized.. ");
                    return null;
                }

                final Optional<MHAAccount> accountOptional = MHAAccount.createFromJSON(responseBody);
                if (accountOptional.isPresent()) {
                    MHAAccount account = accountOptional.get();
                    cache.add(sessionId, account);
                    exchange.putAttachment(MHA_ACCOUNT, account);
                    nextHandler.handleRequest(exchange);
                } else {
                    exchange.setStatusCode(StatusCodes.UNAUTHORIZED);
                    exchange.getResponseSender().send("You are not authorized or account deactivated.. ");
                }
                return null;
            }

            @Override
            public void onThrowable(Throwable t) {
                exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
                exchange.getResponseSender().send("Validating access token: " + t.getMessage());
            }
        });
        exchange.dispatch();

    }

    static final class UsernamePrincipal implements Principal {
        private final String username;

        UsernamePrincipal(String username) {
            this.username = username;
        }

        @Override
        public String getName() {
            return this.username;
        }

        @Override
        public boolean implies(Subject subject) {
            return false;
        }
    }

    static class MHAAccount implements Account {

        private UsernamePrincipal principal;
        private Set<String> roles;

        private MHAAccount(final String username) {
            this.principal = new UsernamePrincipal(username);
        }

        private static Boolean getAsBoolean(JSONObject obj, String property) {
            return Boolean.valueOf(obj.getAsString(property));
        }

        public static Optional<MHAAccount> createFromJSON(final String jsonResponse) {
            JSONParser parser = new JSONParser(JSONParser.MODE_STRICTEST);
            try {
                final JSONObject obj = parser.parse(jsonResponse, JSONObject.class);
                if (obj.containsKey("error"))
                    return Optional.empty();

                if (getAsBoolean(obj, "accountNonExpired") && getAsBoolean(obj, "accountNonLocked") &&
                        getAsBoolean(obj, "credentialsNonExpired") && getAsBoolean(obj, "enabled")) {

                    final MHAAccount account = new MHAAccount(obj.getAsString("username"));
                    final List<JSONObject> authorities = (List<JSONObject>) obj.get("authorities");
                    account.roles = authorities.stream()
                            .map(auth -> auth.getAsString("authority"))
                            .collect(Collectors.toSet());
                    return Optional.of(account);
                }

            } catch (ParseException e) {
                // ignored...
            }
            return Optional.empty();
        }

        @Override
        public Principal getPrincipal() {
            return this.principal;
        }

        @Override
        public Set<String> getRoles() {
            return this.roles;
        }
    }
}
