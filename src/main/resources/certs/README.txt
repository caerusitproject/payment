Certificate material
====================

certs/dev/* contains GENERATED, NON-PRODUCTION test certificates and a private key used only by
DummyIciciClient during local development. They are intentionally bundled so the project can run
without ICICI-issued certificates. They are not trusted or issued by ICICI and must never be used
for UAT or production transactions.

The dev private key is test material and must never protect real customer information or authorize
real bank transactions. Production private keys and certificates must be mounted from a secret
manager/HSM or protected filesystem and must not be committed or packaged.

Before enabling the real HTTP client, obtain ICICI-approved material and confirm the exact wire
format, certificate roles, key sizes, endpoints, API headers, and credentials with the bank.
