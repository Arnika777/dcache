package org.dcache.gplazma.oidc;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.net.InternetDomainName;
import org.codehaus.jackson.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.Principal;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.dcache.auth.BearerTokenCredential;
import org.dcache.auth.EmailAddressPrincipal;
import org.dcache.auth.FullNamePrincipal;
import org.dcache.auth.OidcSubjectPrincipal;
import org.dcache.auth.OpenIdGroupPrincipal;
import org.dcache.gplazma.AuthenticationException;
import org.dcache.gplazma.oidc.exceptions.OidcException;
import org.dcache.gplazma.oidc.helpers.JsonHttpClient;
import org.dcache.gplazma.plugins.GPlazmaAuthenticationPlugin;

import static com.google.common.base.Preconditions.checkArgument;
import static org.dcache.gplazma.util.Preconditions.checkAuthentication;

public class OidcAuthPlugin implements GPlazmaAuthenticationPlugin
{
    private final static Logger LOG = LoggerFactory.getLogger(OidcAuthPlugin.class);
    private final static String OIDC_HOSTNAMES = "gplazma.oidc.hostnames";

    private final LoadingCache<String, JsonNode> cache;
    private final Random random = new Random();
    private Set<String> discoveryDocs;
    private JsonHttpClient jsonHttpClient;

    public OidcAuthPlugin(Properties properties)
    {
        this(properties, new JsonHttpClient());
    }

    @VisibleForTesting
    OidcAuthPlugin(Properties properties, JsonHttpClient client)
    {
        this(properties, client, createLoadingCache(client));
    }

    @VisibleForTesting
    OidcAuthPlugin(Properties properties, JsonHttpClient client, LoadingCache<String, JsonNode> cache)
    {
        String oidcHostnamesProperty = properties.getProperty(OIDC_HOSTNAMES);

        checkArgument(oidcHostnamesProperty != null, OIDC_HOSTNAMES + " not defined");

        Map<Boolean, Set<String>> validHosts = Arrays.stream(oidcHostnamesProperty.split("\\s+"))
                                                     .filter(not(String::isEmpty))
                                                     .collect(
                                                             Collectors.groupingBy(InternetDomainName::isValid,
                                                                     Collectors.toSet())
                                                             );

        checkArgument(!validHosts.containsKey(Boolean.FALSE),
                String.format("Invalid hosts in %s: %s", OIDC_HOSTNAMES,
                        Joiner.on(", ").join(nullToEmpty(validHosts.get(Boolean.FALSE)))));

        checkArgument(validHosts.containsKey(Boolean.TRUE),
                String.format("No hosts specified in %s", OIDC_HOSTNAMES));

        this.discoveryDocs = validHosts.get(Boolean.TRUE);
        this.jsonHttpClient = client;
        this.cache = cache;
    }

    private static LoadingCache<String, JsonNode> createLoadingCache(final JsonHttpClient client)
    {
        return CacheBuilder.newBuilder()
                           .maximumSize(100)
                           .expireAfterAccess(1, TimeUnit.HOURS)
                           .build(
                                   new CacheLoader<String, JsonNode>() {
                                       @Override
                                       public JsonNode load(String hostname) throws OidcException, IOException
                                       {
                                           JsonNode discoveryDoc = client.doGet("https://" +
                                                   hostname +
                                                   "/.well-known/openid-configuration");
                                           if (discoveryDoc != null && discoveryDoc.has("userinfo_endpoint")) {
                                               return discoveryDoc;
                                           } else {
                                               throw new OidcException(hostname,
                                                       "Discovery Document at " + discoveryDoc +
                                                               " does not contain userinfo endpoint url");
                                           }
                                       }
                                   }
                                 );
    }

    private static <T> Predicate<T> not(Predicate<T> t)
    {
        return t.negate();
    }

    @Override
    public void authenticate(Set<Object> publicCredentials,
                             Set<Object> privateCredentials,
                             Set<Principal> identifiedPrincipals)
            throws AuthenticationException
    {
        Set<String> failures = new HashSet<>();
        boolean foundBearerToken = false;

        for (Object credential : privateCredentials) {
            if (credential instanceof BearerTokenCredential) {
                BearerTokenCredential token = (BearerTokenCredential) credential;
                foundBearerToken = true;
                for (String host : discoveryDocs) {
                    try {
                        identifiedPrincipals.addAll(
                                validateBearerTokenWithOpenIdProvider(token,
                                        extractUserInfoEndPoint(cache.get(host)),
                                        host));
                        return;
                    } catch (OidcException oe) {
                        failures.add(oe.getMessage());
                    } catch (ExecutionException e) {
                        failures.add("(\"" + host + "\", " + e.getMessage() + ")");
                    }
                }
            }
        }

        checkAuthentication(foundBearerToken, "No bearer token in the credentials");

        if (failures.size() == 1) {
            throw new AuthenticationException("OpenId Validation Failed: " + failures.iterator().next());
        } else {
            String randomId = randomId();
            LOG.warn("OpenId Validation Failure ({}): {}", randomId, buildErrorMessage(failures));
            throw new AuthenticationException("OpenId Validation Failed check [log entry #" + randomId + "]");
        }
    }

    private Set<Principal> validateBearerTokenWithOpenIdProvider
            (BearerTokenCredential credential, String infoUrl, String host) throws OidcException
    {
        try {
            JsonNode userInfo = getUserInfo(infoUrl, credential.getToken());
            if (userInfo != null && userInfo.has("sub")) {
                LOG.debug("UserInfo from OpenId Provider: {}", userInfo);
                Set<Principal> principals = new HashSet<>();
                addSub(userInfo, principals);
                addNames(userInfo, principals);
                addEmail(userInfo, principals);
                addGroups(userInfo, principals);
                return principals;
            } else {
                throw new OidcException(host, "No OpendId \"sub\"");
            }
        } catch (IllegalArgumentException iae) {
            throw new OidcException(host, "Error parsing UserInfo: " + iae.getMessage());
        } catch (AuthenticationException e) {
            throw new OidcException(host, e.getMessage());
        } catch (IOException e) {
            throw new OidcException(host, "Failed to fetch UserInfo: " + e.getMessage());
        }
    }

    private JsonNode getUserInfo(String url, String token) throws AuthenticationException, IOException
    {
        JsonNode userInfo = jsonHttpClient.doGetWithToken(url, token);
        if (userInfo.has("error")) {
            String error = userInfo.get("error").asText();
            String errorDescription = userInfo.get("error_description").asText();
            throw new AuthenticationException("Error: [" + error + ", " + errorDescription + " ]");
        } else {
            return userInfo;
        }
    }

    private String extractUserInfoEndPoint(JsonNode discoveryDoc)
    {
        if (discoveryDoc.has("userinfo_endpoint")) {
            return discoveryDoc.get("userinfo_endpoint").asText();
        } else {
            return null;
        }
    }

    private void addEmail(JsonNode userInfo, Set<Principal> principals)
    {
        if (userInfo.has("email")) {
            principals.add(new EmailAddressPrincipal(userInfo.get("email").asText()));
        }
    }

    private void addNames(JsonNode userInfo, Set<Principal> principals)
    {
        JsonNode givenName = userInfo.get("given_name");
        JsonNode familyName = userInfo.get("family_name");
        JsonNode fullName = userInfo.get("name");

        if (fullName != null && !fullName.asText().isEmpty()) {
            principals.add(new FullNamePrincipal(fullName.asText()));
        } else if (givenName != null && !givenName.asText().isEmpty()
                && familyName != null && !familyName.asText().isEmpty()) {
            principals.add(new FullNamePrincipal(givenName.asText(), familyName.asText()));
        }
    }

    private boolean addSub(JsonNode userInfo, Set<Principal> principals)
    {
        return principals.add(new OidcSubjectPrincipal(userInfo.get("sub").asText()));
    }

    private void addGroups(JsonNode userInfo, Set<Principal> principals)
    {
        if (userInfo.has("groups") && userInfo.get("groups").isArray()) {
            for (JsonNode group : userInfo.get("groups")) {
                principals.add(new OpenIdGroupPrincipal(group.asText()));
            }
        }
    }

    private <T> Collection<T> nullToEmpty(final Collection<T> collection)
    {
        return collection == null ? Collections.emptySet() : collection;
    }

    private String buildErrorMessage(Set<String> errors)
    {
        return errors.isEmpty() ? "(unknown)" : errors.stream().collect(Collectors.joining(", ", "[", "]"));
    }

    private String randomId()
    {
        byte[] rawId = new byte[6]; // a Base64 char represents 6 bits; 4 chars represent 3 bytes.
        random.nextBytes(rawId);
        return Base64.getEncoder().withoutPadding().encodeToString(rawId);
    }
}
