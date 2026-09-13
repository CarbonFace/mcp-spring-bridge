# Security reports

Do not put credentials, private keys, tokens or customer data in public issues. Until a private security contact is configured on GitHub, report privately to the Cogistra repository maintainer through an existing trusted channel. No email address or security SLA is implied by this file.

This repository currently contains the initial `0.1.0-SNAPSHOT` implementation. It has not been published as a stable release. See the recorded verification and deployment boundaries before adopting it.

Security-sensitive areas include Spring proxy invocation, JWT issuer/audience/account checks, scope and business permission intersections, owner/client isolation for files and operations, redirect matching, PKCE, refresh rotation, and replay handling. The server can enforce an immutable confirmed payload reference; it cannot independently prove what a user said in a client conversation.

Local filesystem persistence requires exclusive ownership of a private directory and one application process. Clustered deployments must provide the documented shared storage SPIs and authoritative business recovery. Do not interpret a process restart or unknown execution result as permission to repeat a write.
