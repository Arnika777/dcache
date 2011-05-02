package org.dcache.gplazma.plugins;

import java.io.IOException;
import java.security.Principal;
import static java.util.Collections.unmodifiableSet;
import static java.util.Collections.emptySet;
import java.util.Set;
import java.net.URL;
import java.nio.charset.Charset;

import junit.framework.AssertionFailedError;

import org.dcache.auth.FQANPrincipal;
import org.dcache.auth.GroupNamePrincipal;
import org.dcache.auth.UidPrincipal;
import org.dcache.gplazma.AuthenticationException;
import org.globus.gsi.jaas.GlobusPrincipal;
import gplazma.authz.util.NameRolePair;

import org.junit.Test;
import static org.junit.Assert.*;

import static com.google.common.collect.Sets.newHashSet;
import com.google.common.io.Resources;

public class VoRoleMapPluginTest
{
    public static final String DN_TIGRAN =
        "/O=GermanGrid/OU=DESY/CN=Tigran Mkrtchyan";
    public static final String DN_KLAUS =
        "/O=GermanGrid/OU=DESY/CN=Klaus Maus";
    public static final String DN_FLAVIA =
        "/DC=ch/DC=cern/OU=Organic Units/OU=Users/CN=flavia/CN=388195/CN=Flavia Donno";

    public static final String FQAN_DTEAM_LONG =
        "/dteam/Role=NULL/Capability=NULL";
    public static final String FQAN_DTEAM_SHORT =
        "/dteam";
    public static final String FQAN_INVALID =
        "/invalid/ROLE=NULL";
    public static final String FQAN_CMS_LONG =
        "/cms/Role=NULL/Capability=NULL";

    public static final int UID = 666;

    public static final String USERNAME_KLAUS = "klaus";
    public static final String USERNAME_HORST = "horst";
    public static final String USERNAME_DTEAM = "dteamuser";
    public static final String USERNAME_TIGRAN = "tigran";

    private final static URL TEST_FIXTURE_WITH_WILDCARDS =
        Resources.getResource("org/dcache/gplazma/plugins/vorolemap-wildcard.fixture");
    private final static URL TEST_FIXTURE_WITHOUT_WILDCARDS =
        Resources.getResource("org/dcache/gplazma/plugins/vorolemap-no-wildcard.fixture");

    private final static Set<Principal> EMPTY_SET = emptySet();

    private SourceBackedPredicateMap<NameRolePair, String>
        loadFixture(URL fixture)
        throws IOException
    {
        return new SourceBackedPredicateMap(new MemoryLineSource(Resources.readLines(fixture, Charset.defaultCharset())), new VOMapLineParser());
    }

    public void check(URL fixture,
                      Set<? extends Principal> principals,
                      Set<? extends Principal> expectedPrincipals,
                      Set<? extends Principal> expectedAuthorizedPrincipals)
        throws AuthenticationException, IOException
    {
        VoRoleMapPlugin plugin = new VoRoleMapPlugin(loadFixture(fixture));
        Set<Principal> authorizedPrincipals = newHashSet();
        plugin.map(null, (Set<Principal>) principals, authorizedPrincipals);
        assertEquals("Principals don't match",
                     expectedPrincipals, principals);
        assertEquals("Authorized principals don't match",
                     expectedAuthorizedPrincipals, authorizedPrincipals);
    }

    @Test(expected=NullPointerException.class)
    public void testValidArgsWithAllNullParams()
        throws AuthenticationException, IOException
    {
        VoRoleMapPlugin plugin =
            new VoRoleMapPlugin(loadFixture(TEST_FIXTURE_WITH_WILDCARDS));
        plugin.map(null, null, null);
    }

    @Test
    public void testSinglePrincipalWithValidDNValidRole()
        throws AuthenticationException, IOException
    {
        check(TEST_FIXTURE_WITH_WILDCARDS,
              newHashSet(new GlobusPrincipal(DN_TIGRAN),
                         new FQANPrincipal(FQAN_DTEAM_SHORT, true)),
              newHashSet(new GlobusPrincipal(DN_TIGRAN),
                         new FQANPrincipal(FQAN_DTEAM_SHORT, true),
                         new GroupNamePrincipal(USERNAME_TIGRAN, true)),
              newHashSet(new GlobusPrincipal(DN_TIGRAN),
                         new FQANPrincipal(FQAN_DTEAM_SHORT, true),
                         new GroupNamePrincipal(USERNAME_TIGRAN, true)));
    }

    @Test
    public void testMultiplePrincipalsWithValidDNValidRole()
        throws AuthenticationException, IOException
    {
        check(TEST_FIXTURE_WITH_WILDCARDS,
              newHashSet(new UidPrincipal(UID),
                         new GlobusPrincipal(DN_TIGRAN),
                         new GlobusPrincipal(DN_KLAUS),
                         new FQANPrincipal(FQAN_DTEAM_LONG, true)),
              newHashSet(new UidPrincipal(UID),
                         new GlobusPrincipal(DN_TIGRAN),
                         new GlobusPrincipal(DN_KLAUS),
                         new FQANPrincipal(FQAN_DTEAM_LONG, true),
                         new GroupNamePrincipal(USERNAME_TIGRAN, true),
                         new GroupNamePrincipal(USERNAME_DTEAM, true)),
              newHashSet(new GlobusPrincipal(DN_TIGRAN),
                         new GlobusPrincipal(DN_KLAUS),
                         new FQANPrincipal(FQAN_DTEAM_LONG, true),
                         new GroupNamePrincipal(USERNAME_TIGRAN, true),
                         new GroupNamePrincipal(USERNAME_DTEAM, true)));
    }

    /**
     * Tests throwing of AuthenticationException if no matching
     * combination in vorolemap exists. Uses vorolemap file without
     * wildcards.
     *
     * @throws AuthenticationException
     * @throws IOException
     */
    @Test(expected=AuthenticationException.class)
    public void testWithInvalidDNValidRole()
        throws AuthenticationException, IOException
    {
        check(TEST_FIXTURE_WITHOUT_WILDCARDS,
              unmodifiableSet(newHashSet(new FQANPrincipal(FQAN_DTEAM_LONG, true),
                                         new FQANPrincipal(FQAN_CMS_LONG, true))),
              EMPTY_SET,
              EMPTY_SET);
    }

    /**
     * Similar to testAuthenticationWithInvalidDNValidRole, but with
     * valid dn and invalid role.
     *
     * @throws AuthenticationException
     * @throws IOException
     */
    @Test(expected=AuthenticationException.class)
    public void testWithValidDNInvalidRole()
        throws AuthenticationException, IOException
    {
        check(TEST_FIXTURE_WITHOUT_WILDCARDS,
              unmodifiableSet(newHashSet(new UidPrincipal(UID),
                                         new GlobusPrincipal(DN_TIGRAN),
                                         new FQANPrincipal(FQAN_INVALID, true))),
              EMPTY_SET,
              EMPTY_SET);
    }

    /**
     * The "invalid" DN/Role is only matched against the "* horst" entry
     * @throws AuthenticationException
     * @throws IOException
     */
    @Test
    public void testWithInvalidDNInvalidRole()
        throws AuthenticationException, IOException
    {
        check(TEST_FIXTURE_WITH_WILDCARDS,
              newHashSet(new GlobusPrincipal(DN_FLAVIA),
                         new FQANPrincipal(FQAN_INVALID, true)),
              newHashSet(new GlobusPrincipal(DN_FLAVIA),
                         new FQANPrincipal(FQAN_INVALID, true),
                         new GroupNamePrincipal(USERNAME_HORST, true)),
              newHashSet(new GlobusPrincipal(DN_FLAVIA),
                         new FQANPrincipal(FQAN_INVALID, true),
                         new GroupNamePrincipal(USERNAME_HORST, true)));
    }

    /**
     * This principal is not even matched by the wildcard entries
     * because of the '!'.
     *
     * @throws AuthenticationException
     * @throws IOException
     */
    @Test(expected=AuthenticationException.class)
    public void testWithNonsensePrincipalWithWC()
        throws AuthenticationException, IOException
    {
        check(TEST_FIXTURE_WITH_WILDCARDS,
              unmodifiableSet(newHashSet(new UidPrincipal(UID),
                                         new FQANPrincipal("TotalNonsense!", true))),
              EMPTY_SET,
              EMPTY_SET);
    }
}
