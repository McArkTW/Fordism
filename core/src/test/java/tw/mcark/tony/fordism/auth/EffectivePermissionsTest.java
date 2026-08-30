package tw.mcark.tony.fordism.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a person may do is the union of the grants on every group holding them — never a per-user
 * field, so a capability is revoked by editing one group rather than auditing every account.
 */
class EffectivePermissionsTest {

    @TempDir
    Path stateDir;

    private UserStore users;
    private GroupStore groups;
    private User dana;

    @BeforeEach
    void setUp() {
        users = new UserStore(stateDir);
        groups = new GroupStore(stateDir);
        SeededGroups.into(groups);
        dana = users.create(User.withPassword("Dana@Example.COM", "Dana", PasswordHash.of("hunter2xx")));
    }

    private void join(String groupName) {
        Group group = groups.findByName(groupName).orElseThrow();
        groups.update(group.withMember(dana.id()));
    }

    @Test
    void a_user_in_no_group_can_do_nothing() {
        assertEquals(Set.of(), groups.grantsFor(dana.id()));
        for (Permission permission : Permission.values()) {
            assertFalse(groups.allows(dana.id(), permission), permission.id());
        }
    }

    @Test
    void grants_add_up_across_every_group_a_user_is_in() {
        join("viewers");
        join("operators");
        Set<String> effective = groups.grantsFor(dana.id());
        assertTrue(effective.contains("run.*"), effective.toString());
        assertTrue(effective.contains("workflow.run"), effective.toString());

        // viewers alone could not answer a question; operators' run.* covers it, and the union
        // is what gets asked — not whichever group happened to be listed first.
        assertTrue(groups.allows(dana.id(), Permission.RUN_ANSWER));
        assertTrue(groups.allows(dana.id(), Permission.RUN_WORKSPACE_DOWNLOAD));
        assertFalse(groups.allows(dana.id(), Permission.WORKFLOW_WRITE));
        assertFalse(groups.allows(dana.id(), Permission.USER_READ));
    }

    @Test
    void a_viewer_can_read_the_machine_and_change_nothing() {
        join("viewers");
        assertTrue(groups.allows(dana.id(), Permission.WORKFLOW_READ));
        assertTrue(groups.allows(dana.id(), Permission.RUN_READ));
        assertFalse(groups.allows(dana.id(), Permission.WORKFLOW_RUN));
        assertFalse(groups.allows(dana.id(), Permission.SKILL_WRITE));
        // A workspace zip is not "reading a run": it is whatever the agent left on disk.
        assertFalse(groups.allows(dana.id(), Permission.RUN_WORKSPACE_DOWNLOAD));
    }

    /**
     * v1.1 split three coarse permissions into finer ones. The seeded groups are written as
     * subtrees, so they must come through that split holding exactly what they held before.
     */
    @Test
    void the_finer_permissions_change_nothing_for_a_group_that_grants_a_subtree() {
        join("maintainers");
        assertTrue(groups.allows(dana.id(), Permission.WORKFLOW_DELETE), "workflow.* still deletes");
        assertTrue(groups.allows(dana.id(), Permission.SKILL_PLUGIN_WRITE), "skill.* still installs plugins");
        assertTrue(groups.allows(dana.id(), Permission.RUN_ABANDON), "run.* still abandons");
    }

    /** And a group written as a bare leaf narrows, which is the whole point of splitting them. */
    @Test
    void a_group_granting_only_skill_write_no_longer_installs_a_plugin_from_a_url() {
        Group narrow = groups.create(Group.named("skill-authors", List.of("skill.read", "skill.write")));
        groups.update(narrow.withMember(dana.id()));

        assertTrue(groups.allows(dana.id(), Permission.SKILL_WRITE));
        assertTrue(groups.allows(dana.id(), Permission.SKILL_READ), "the plugin list is still a read");
        assertFalse(groups.allows(dana.id(), Permission.SKILL_PLUGIN_WRITE),
                "pointing the instance at a URL is not the same power as editing a SKILL.md");
    }

    @Test
    void every_seeded_group_can_manage_its_members_own_api_tokens() {
        // Safe for viewers too: a token is the intersection of its grants and its owner's, so a
        // viewer's token can only ever read.
        for (String groupName : List.of("admins", "maintainers", "operators", "viewers")) {
            User member = users.create(User.withPassword(groupName + "@example.com", groupName,
                    PasswordHash.of("hunter2xx")));
            Group group = groups.findByName(groupName).orElseThrow();
            groups.update(group.withMember(member.id()));
            assertTrue(groups.allows(member.id(), Permission.TOKEN_WRITE), groupName);
            assertTrue(groups.allows(member.id(), Permission.TOKEN_READ), groupName);
        }
    }

    @Test
    void an_admin_star_covers_permissions_nobody_has_invented_yet() {
        join("admins");
        for (Permission permission : Permission.values()) {
            assertTrue(groups.allows(dana.id(), permission), permission.id());
        }
        assertTrue(PermissionMatcher.matches(PermissionMatcher.EVERYTHING, "something.new"));
    }

    @Test
    void seeding_twice_changes_nothing_and_never_re_widens_a_narrowed_group() {
        int before = groups.all().size();
        Group operators = groups.findByName("operators").orElseThrow();
        groups.update(new Group(operators.id(), operators.name(), operators.memberUserIds(),
                List.of("workflow.read")));

        SeededGroups.into(groups);

        assertEquals(before, groups.all().size());
        assertEquals(List.of("workflow.read"), groups.findByName("operators").orElseThrow().grants());
    }

    @Test
    void the_last_group_granting_everything_to_a_real_user_is_what_the_guard_protects() {
        join("admins");
        List<Group> all = groups.all();
        assertTrue(GroupStore.fullAccessSurvives(users.all(), all));

        // Emptying the admins group, deleting it, or deleting its only member all break it.
        List<Group> emptied = new ArrayList<>();
        for (Group group : all) {
            emptied.add(group.withoutMember(dana.id()));
        }
        assertFalse(GroupStore.fullAccessSurvives(users.all(), emptied));

        List<Group> withoutAdmins = new ArrayList<>(all);
        withoutAdmins.removeIf(Group::grantsEverything);
        assertFalse(GroupStore.fullAccessSurvives(users.all(), withoutAdmins));

        assertFalse(GroupStore.fullAccessSurvives(List.of(), all));
    }

    @Test
    void a_group_holding_only_a_deleted_user_does_not_count_as_full_access() {
        // The members list can outlive an account; "somebody can still administer this" has to
        // mean an account that exists, not an id that once did.
        Group admins = groups.findByName(SeededGroups.ADMINS).orElseThrow();
        groups.update(admins.withMember("a-user-that-was-deleted"));
        assertFalse(GroupStore.fullAccessSurvives(users.all(), groups.all()));
    }

    @Test
    void an_email_is_stored_and_found_case_insensitively() {
        assertEquals("dana@example.com", dana.email());
        assertTrue(users.findByEmail("DANA@example.com").isPresent());
        assertThrows(IllegalArgumentException.class,
                () -> users.create(User.withPassword("dana@EXAMPLE.com", "Twin", PasswordHash.of("hunter2xx"))));
    }

    @Test
    void state_survives_a_restart_because_the_file_is_the_truth() {
        join("admins");
        GroupStore reopened = new GroupStore(stateDir);
        assertTrue(reopened.allows(dana.id(), Permission.USER_WRITE));
        assertEquals(List.of(SeededGroups.ADMINS), reopened.groupNamesFor(dana.id()));
    }
}
