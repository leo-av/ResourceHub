  
\---  
name: document-version-management  
description: Manages documentary version approval and archiving. Use whenever the user approves, validates, accepts, finalizes or establishes a new official version of a project document.  
\---

\# Document Version Management

\#\# Objective

Manage the lifecycle of project documentation versions.

Whenever the user explicitly approves a new version of a document, archive all previous versions of that same document inside an \`@old\` directory located in the document's current parent directory.

\#\# Approval detection

Activate this procedure when the user explicitly communicates that a document version has been approved.

Examples:  
\- "Apruebo esta versión."  
\- "Esta versión queda aprobada."  
\- "Damos por válida la versión v0.2."  
\- "Esta es la versión definitiva."  
\- "Acepto la nueva versión documental."  
\- "Ya podemos dar por cerrada esta versión."

Do not activate when:  
\- The user is merely reviewing a document.  
\- The user requests changes or corrections.  
\- The user asks for a proposal or draft.  
\- The user discusses a possible approval without explicitly granting it.

\#\# Archiving procedure

When a version is approved:

1\. Identify the exact document and its approved version.  
2\. Locate its current file and parent directory.  
3\. Identify previous versions of the same document.  
4\. Determine which versions are older than the approved version.  
5\. Create an \`@old\` directory inside the document's parent directory if it does not exist.  
6\. Move all identified older versions into that directory.  
7\. Keep the approved version in its original location.  
8\. Do not move unrelated documents, drafts, templates or supplementary resources.

\#\# Version identification

\- Recognize version identifiers such as v0.1, v0.2, v1.0, v1.1 and v2.0.  
\- Compare versions numerically, not alphabetically.  
\- Only archive versions belonging to the same logical document.  
\- Never archive a version newer than the approved version.  
\- Never assume two files belong to the same document just because their names are similar.

\#\# Safety rules

\- Never delete documentary files.  
\- Never overwrite an existing file in \`@old\`.  
\- If a destination file already exists and has different content, stop and ask the user how to proceed.  
\- If the approved version cannot be identified unambiguously, ask the user.  
\- Do not modify document contents during archiving.  
\- Preserve original filenames and extensions.  
\- Do not archive unrelated documents.

\#\# Execution and reporting

Once approval is explicitly granted, execute the archiving operation without requesting a second confirmation, provided all files and versions have been identified unambiguously.

After execution, report:

\- Approved document and version.  
\- Number of archived versions.  
\- Files moved.  
\- Location of the \`@old\` directory.  
\- Any conflicts or files that could not be archived.

Example:

Document approved: RF-001 \- Requisitos funcionales  
Approved version: v0.3

Archived:  
\- RF-001-v0.1.md  
\- RF-001-v0.2.md

Current:  
\- RF-001-v0.3.md

Archive location:  
\- docs/01-requirements/@old/

