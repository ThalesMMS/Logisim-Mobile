"""Synthetic literal formatting derived from AOSP15, not an observed device dump."""
import unittest
from check_binding import BindingError, FULL, SHORT, canonical_component, check_activity_binding, check_accessibility_state, check_cross_evidence

ACTIVITY = """ACTIVITY MANAGER SERVICES (dumpsys activity services)
  User 0 active services:
  * ServiceRecord{a1 u0 com.google.android.marvin.talkback/.TalkBackService c:android}
    intent={cmp=com.google.android.marvin.talkback/.TalkBackService}
    packageName=com.google.android.marvin.talkback
    processName=com.google.android.marvin.talkback
    app=ProcessRecord{b1 8888:com.google.android.marvin.talkback/u0a123}
    Bindings:
    * IntentBindRecord{c1 CREATE}:
      intent={cmp=com.google.android.marvin.talkback/.TalkBackService}
      binder=android.os.BinderProxy@d1
      requested=true received=true hasBound=true doRebind=false
      * Client AppBindRecord{e1 ProcessRecord{b2 777:system/1000}}
        Per-process Connections:
          ConnectionRecord{f1 u0 CR FGSA CAPS com.google.android.marvin.talkback/.TalkBackService:@a2 flags=0x2101001}
    All Connections:
      ConnectionRecord{f1 u0 CR FGSA CAPS com.google.android.marvin.talkback/.TalkBackService:@a2 flags=0x2101001}
"""
A11Y = """User state[
     attributes:{id=0, touchExplorationEnabled=true, installedServiceCount=3}
     Bound services:{Service[label=Screen reader, feedbackType[FEEDBACK_SPOKEN, FEEDBACK_HAPTIC], capabilities=3, eventTypes[TYPE_VIEW_CLICKED], notificationTimeout=100, requestA11yBtn=false]}
     Enabled services:{{com.google.android.marvin.talkback/.TalkBackService}}
     Binding services:{}
     Client list info:{
          Registered clients:{
[com.google.android.marvin.talkback]
}]
"""


# Exact package-array footer shape from an already captured OFF dump. No ON observation is claimed.
REAL_REGISTERED_FOOTER = "[com.google.android.as][com.google.android.apps.wellbeing][com.google.android.googlequicksearchbox][com.google.android.googlequicksearchbox][thalesmms.logisim.mobile.e2e][com.google.android.tts][com.google.android.inputmethod.latin][com.google.android.gsf, com.google.android.gms][com.google.android.apps.nexuslauncher][com.google.android.marvin.talkback][thalesmms.logisim.mobile.e2e.test]}]\n"
REALISTIC_FOOTER_A11Y = A11Y.replace('[com.google.android.marvin.talkback]\n}]\n', REAL_REGISTERED_FOOTER)


class BindingTests(unittest.TestCase):
    def check(self, activity=ACTIVITY, accessibility=A11Y, pid=777):
        return check_cross_evidence(activity, accessibility, system_server_pid=pid)

    def reject(self, activity=ACTIVITY, accessibility=A11Y, pid=777):
        with self.assertRaises(BindingError):
            self.check(activity, accessibility, pid)

    def test_literal_positive_and_honest_limits(self):
        result = self.check()
        self.assertEqual(result['result'], 'PASS_AMS_BINDING_AND_A11Y_STATE_ONLY')
        self.assertEqual(result['ams']['component'], FULL)
        self.assertEqual(result['ams']['system_server_pid'], 777)
        self.assertEqual(result['ams']['connection'], ('f1', FULL, 'a2', 0x2101001))
        self.assertIsNone(result['accessibility']['component_in_bound_dump'])
        self.assertIn('CORRELATION_ONLY', result['identity_join'])

    def test_full_and_short_tokens_are_equivalent_only_for_exact_component(self):
        self.assertEqual(canonical_component(FULL), FULL)
        self.assertEqual(canonical_component(SHORT), FULL)
        self.assertEqual(self.check(ACTIVITY.replace(SHORT, FULL))['ams']['component'], FULL)
        for value in [SHORT + 'Extra', FULL + ' ', 'TalkBack', '.TalkBackService', 'fake/' + FULL.split('/')[1]]:
            self.assertIsNone(canonical_component(value))

    def test_binding_flags_false_are_rejected(self):
        for field in ['requested', 'received', 'hasBound']:
            with self.subTest(field=field):
                self.reject(ACTIVITY.replace(field + '=true', field + '=false'))
        self.reject(ACTIVITY.replace('doRebind=false', 'doRebind=true'))

    def test_null_or_unrecognized_binder_is_rejected(self):
        self.reject(ACTIVITY.replace('binder=android.os.BinderProxy@d1', 'binder=null'))
        self.reject(ACTIVITY.replace('binder=android.os.BinderProxy@d1', 'binder=not-null'))

    def test_other_component_and_partial_component_are_rejected(self):
        self.reject(ACTIVITY.replace(SHORT, 'com.example/.OtherService'))
        self.reject(ACTIVITY.replace(SHORT, SHORT + 'Other'))

    def test_installed_or_enabled_without_active_binding_is_not_evidence(self):
        self.reject('ACTIVITY MANAGER SERVICES (dumpsys activity services)\n')
        no_binding = ACTIVITY.split('    Bindings:', 1)[0] + '    All Connections:\n'
        self.reject(no_binding)
        self.reject(ACTIVITY.replace('app=ProcessRecord{b1 8888:com.google.android.marvin.talkback/u0a123}', 'app=null'))

    def test_wrong_or_unknown_system_pid_name_or_uid_is_rejected(self):
        for pid in [None, 0, True, 778]:
            with self.subTest(pid=pid):
                self.reject(pid=pid)
        self.reject(ACTIVITY.replace('777:system/1000', '777:system_server/1000'))
        self.reject(ACTIVITY.replace('777:system/1000', '777:system/1001'))

    def test_dead_missing_or_unlinked_connection_is_rejected(self):
        self.reject(ACTIVITY.replace('u0 CR FGSA CAPS', 'u0 CR FGSA CAPS DEAD'))
        self.reject(ACTIVITY.replace('\n      ConnectionRecord{f1', '\n      ConnectionRecord{ff'))
        self.reject(ACTIVITY.split('    All Connections:', 1)[0])

    def test_fields_from_another_binding_cannot_complete_the_target_binding(self):
        false_first = ACTIVITY.replace('received=true', 'received=false')
        second = """    * IntentBindRecord{c2 CREATE}:
      intent={cmp=com.google.android.marvin.talkback/.TalkBackService}
      binder=android.os.BinderProxy@d2
      requested=true received=true hasBound=true doRebind=false
      * Client AppBindRecord{e2 ProcessRecord{b3 900:other/1000}}
        Per-process Connections:
          ConnectionRecord{f2 u0 CR com.google.android.marvin.talkback/.TalkBackService:@a3 flags=0x1}
"""
        self.reject(false_first.replace('    All Connections:', second + '    All Connections:'))

    def test_duplicate_active_service_is_ambiguous(self):
        self.reject(ACTIVITY + ACTIVITY.split('  User 0 active services:\n', 1)[1])

    def test_other_active_user_or_historical_record_is_not_current_service(self):
        self.reject(ACTIVITY.replace('  User 0 active services:', '  User 10 active services:').replace('ServiceRecord{a1 u0 ', 'ServiceRecord{a1 u10 '))
        self.reject(ACTIVITY.replace('  User 0 active services:', '  Last ANR service:'))

    def test_root_identity_cannot_be_borrowed_from_nested_intent(self):
        self.reject(ACTIVITY.replace('    intent={cmp=' + SHORT + '}', '    intent={cmp=com.example/.Other}', 1))
        self.reject(ACTIVITY.replace('packageName=com.google.android.marvin.talkback', 'packageName=com.example'))

    def test_touch_exploration_false_or_wrong_user_is_rejected(self):
        self.reject(accessibility=A11Y.replace('touchExplorationEnabled=true', 'touchExplorationEnabled=false'))
        self.reject(accessibility=A11Y.replace('attributes:{id=0', 'attributes:{id=10'))
        self.reject(accessibility=A11Y.replace('touchExplorationEnabled=true', 'touchExplorationEnabled=false') + A11Y.replace('attributes:{id=0', 'attributes:{id=10'))

    def test_enabled_or_registered_identity_does_not_replace_bound_record(self):
        empty = A11Y.replace('Bound services:{Service[label=Screen reader, feedbackType[FEEDBACK_SPOKEN, FEEDBACK_HAPTIC], capabilities=3, eventTypes[TYPE_VIEW_CLICKED], notificationTimeout=100, requestA11yBtn=false]}', 'Bound services:{}')
        self.reject(accessibility=empty)
        self.reject(accessibility=A11Y.replace('capabilities=3', 'capabilities=1'))
        self.reject(accessibility=A11Y.replace('feedbackType[FEEDBACK_SPOKEN, FEEDBACK_HAPTIC]', 'feedbackType[FEEDBACK_HAPTIC]'))

    def test_label_is_observed_but_never_identity(self):
        result = self.check(accessibility=A11Y.replace('label=Screen reader', 'label=Any localized label'))
        self.assertEqual(result['ams']['component'], FULL)
        self.assertEqual(result['accessibility']['bound_label_observed_only'], 'Any localized label')
        self.assertIsNone(result['accessibility']['component_in_bound_dump'])

    def test_enabled_and_registered_tokens_are_not_used_as_proof(self):
        text = A11Y.replace('Enabled services:{{com.google.android.marvin.talkback/.TalkBackService}}', 'Enabled services:{}').replace('[com.google.android.marvin.talkback]', '[]')
        self.assertEqual(self.check(accessibility=text)['ams']['component'], FULL)

    def test_concatenated_registered_clients_footer_format(self):
        # The positive state remains the synthetic ON fixture; only the footer format is real.
        self.assertEqual(self.check(accessibility=REALISTIC_FOOTER_A11Y)['result'], 'PASS_AMS_BINDING_AND_A11Y_STATE_ONLY')

    def test_realistic_off_footer_is_parsed_but_does_not_prove_reader_on(self):
        off = REALISTIC_FOOTER_A11Y.replace('touchExplorationEnabled=true', 'touchExplorationEnabled=false')
        with self.assertRaises(BindingError) as caught:
            check_accessibility_state(off)
        self.assertEqual(caught.exception.code, 'touch_exploration_not_confirmed')

    def test_source_defined_early_footer_does_not_borrow_orphan_bound_lines(self):
        before_bound, rest = A11Y.split('     Bound services:', 1)
        orphan = '     Bound services:' + rest.split('     Client list info:', 1)[0]
        early = before_bound + '     Client list info:{\n          Registered clients:{\n' + REAL_REGISTERED_FOOTER
        self.reject(accessibility=early + orphan)

    def test_truncated_concatenated_footer_is_not_closed_by_unrelated_later_text(self):
        truncated = REALISTIC_FOOTER_A11Y.rsplit('}]\n', 1)[0] + '\nOther section:\n     Registered clients:{\n[]}]\n'
        self.reject(accessibility=truncated)

    def test_closed_user_state_cannot_borrow_orphan_bound_lines(self):
        for closer in ["]", "}]"]:
            with self.subTest(closer=closer):
                self.reject(accessibility=A11Y.replace('     Bound services:', closer + '\n     Bound services:', 1))

    def test_truncated_user_state_is_rejected(self):
        self.reject(accessibility=A11Y.replace('}]\n', ''))

    def test_multiple_bound_records_are_rejected_without_component_join(self):
        record = 'Service[label=Other, feedbackType[FEEDBACK_SPOKEN], capabilities=3, eventTypes[TYPE_VIEW_CLICKED], notificationTimeout=100, requestA11yBtn=false]'
        self.reject(accessibility=A11Y.replace('requestA11yBtn=false]}', 'requestA11yBtn=false],\n                     ' + record + '}'))


if __name__ == '__main__':
    unittest.main()
