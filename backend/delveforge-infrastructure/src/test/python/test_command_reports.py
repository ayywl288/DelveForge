import importlib.util
import pathlib
import tempfile
import unittest


MODULE = pathlib.Path(__file__).parents[2] / 'main/resources/agent/test_reports.py'
SPEC = importlib.util.spec_from_file_location('command_reports', MODULE)
reports = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(reports)


class CommandReportsTest(unittest.TestCase):
    def test_only_fresh_reports_supply_details_and_tracked_fixtures_are_excluded(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            old = root / 'TEST-old.xml'
            old.write_text('<testsuite><testcase name="old"/></testsuite>')
            before = reports.collect(root, [])['fingerprints']
            self.assertEqual('UNAVAILABLE', reports.collect(root, [], before)['availability'])
            (root / 'TEST-fixture.xml').write_text('<testsuite><testcase name="fake"/></testsuite>')
            (root / 'TEST-live.xml').write_text('<testsuite><testcase classname="A" name="failure"><failure/></testcase><testcase name="skip"><skipped/></testcase></testsuite>')
            result = reports.collect(root, ['TEST-fixture.xml'], before)
            self.assertEqual({'A#failure': 'FAILED', '#skip': 'SKIPPED'}, result['cases'])
            self.assertEqual(1, result['executedTests'])
            self.assertEqual(1, result['failures'])
            self.assertEqual(1, result['skipped'])

    def test_parser_unavailable_is_not_invented_zero(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            report = root / 'TEST-malformed.xml'
            report.write_text('<invalid')
            result = reports.collect(root, [], {})
            self.assertEqual('UNAVAILABLE', result['availability'])
            self.assertNotIn('executedTests', result)


if __name__ == '__main__':
    unittest.main()
