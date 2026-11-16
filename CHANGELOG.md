# ServerHoster 2.5.1

- After a version change, setup checks the folder in `workingDir` and says whether a child was found (`server.properties` or a JAR).
- When a child is found, that folder stays. The wizard still asks only for onboarding answers that were missing. When none are missing, it shows Update 2.5.1.
- When no child is found, the wizard asks for the folder or a new server. The entered folder is checked before continuing, and a rejection is shown under the field. Creating a new server follows first-time setup, including version selection, in `./child-server`.
