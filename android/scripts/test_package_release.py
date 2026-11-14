import unittest

from package_release import signer_digest


class SignerOutputTests(unittest.TestCase):
    def test_stable_and_new_build_tools_output(self):
        digest = "ab" * 32
        for label in ("Signer #1", "Signer #1:", "V2 Signer:", "V3 Signer:"):
            with self.subTest(label=label):
                self.assertEqual(digest, signer_digest(f"{label} certificate SHA-256 digest: {digest}\n"))
        colon_hex = ":".join(["AB"] * 32)
        self.assertEqual(digest, signer_digest(f"Signer #1 certificate SHA-256 digest: {colon_hex}\r\n"))

    def test_matching_scheme_receipts_are_consistent(self):
        digest = "12" * 32
        self.assertEqual(digest, signer_digest(
            f"V2 Signer: certificate SHA-256 digest: {digest}\n"
            f"V3 Signer: certificate SHA-256 digest: {digest}\n"))

    def test_missing_malformed_or_conflicting_certificates_fail(self):
        for text in ("", "Signer #1 certificate SHA-1 digest: " + "ab" * 20,
                     "Signer #1 certificate SHA-256 digest: deadbeef",
                     "Signer #1 certificate SHA-256 digest: " + "ab" * 32 + "\n"
                     "Signer #2 certificate SHA-256 digest: " + "cd" * 32):
            with self.subTest(text=text), self.assertRaises(ValueError):
                signer_digest(text)


if __name__ == "__main__":
    unittest.main()
