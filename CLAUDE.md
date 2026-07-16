\# GhostScreen



GhostScreen is a native Android application written entirely in Kotlin using Jetpack Compose.



This repository DOES NOT use Flutter.



Architecture:



\- MVVM

\- Repository pattern

\- Room

\- AccessibilityService

\- WindowManager Overlay

\- Secure Preferences (Tink)

\- Material 3



Important docs



docs/



01\_PROJECT\_OVERVIEW.md

02\_ARCHITECTURE.md

03\_CODEBASE\_GUIDE.md

04\_BUGS\_AND\_DEFICIENCIES.md

05\_IMPROVEMENT\_ROADMAP.md

06\_DEPENDENCY\_GRAPH.md

07\_CODE\_METRICS.md

08\_REFACTORING\_PLAN.md

09\_PRODUCTION\_RELEASE\_CHECKLIST.md

10\_AI\_CONTEXT.md

ARCHITECTURE\_AUDIT\_REPORT.md



Development Rules



Never rewrite architecture without approval.



Never remove overlay touch passthrough.



Always compile after edits.



Prefer small commits.



Always explain why a change is needed.



Never guess.



Search the repository before making changes.



Read related files before editing.



Prefer extending existing architecture over creating new abstractions.



Do not increase complexity unnecessarily.



Follow Material 3.



Keep accessibility compatibility.



Never modify generated files.

