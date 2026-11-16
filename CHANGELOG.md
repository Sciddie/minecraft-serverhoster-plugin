# ServerHoster 2.5.0

- One child: Fabric, Paper, or a local custom JAR. The default type is `fabric`.
- The web dashboard handles setup, start, stop, backups, and schedules. `/child` shows the child status and the dashboard URL.
- Backups are local ZIP files or FTP/FTPS.
- Setup creates the child in `./child-server`.
- Diagnostics show the current memory reading. A real memory emergency writes `memory-incident.log`.
- A scheduled backup that has waited five hours for an empty child warns once and then keeps waiting.
- Upgrade by replacing the plugin jar. `type` must be `fabric`, `paper`, or `custom`. Config, password, secrets, and the child directory stay in place.
