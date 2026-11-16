# Minimal outer Spigot (host for ServerHoster)

The outer Spigot only exists so Zap-Hosting starts a JVM; the real game runs as the child
(Fabric on port 2063). Goal: outer server as small as possible.

1. Memory: package has 12G total. Keep the outer Spigot's actual use small and lower its Xmx if
   the panel allows. Child default Xms/Xmx = 2G/5G. Leave headroom for JVM overhead/native memory
   (metaspace, threads, direct buffers) of BOTH processes; a cgroup limit can still trigger recovery.
   If the outer Xmx cannot be constrained or cgroup headroom is low, lower the child Xmx further.
   (AlwaysPreTouch is not used.)
2. Merge server.properties / bukkit.yml / spigot.yml keys from this folder. Do NOT change the
   outer server-port. The void flat world + generate-structures=false keeps the world tiny;
   delete existing world folders before first start to regenerate as flat void.
3. This example is not applied by ServerHoster. white-list=true + max-players=1 does not by itself stop a whitelisted player. The plugin rejects outer logins while minimization is staged or active. Players connect to
   <host>:2063. Port 2064 stays free (second instance/query).
4. The child's console appears in the Spigot console prefixed "[Child] ".
   Send child commands from the web dashboard. `/child` only prints status and
   the dashboard URL.
5. Always stop the server via the panel/`stop` (not "kill"), so the plugin can stop the child
   cleanly (up to 60s). Check Zap's stop timeout is >60s if configurable.
6. Optional: remove unneeded plugins; set `settings.timings`/bstats off in plugins/bStats/config.yml.
