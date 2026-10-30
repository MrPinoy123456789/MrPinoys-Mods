# Serving the resource pack to players

Foundry contributes textures, models and generated client item definitions to the pack Polymer builds at
server start (`polymer/resource_pack.zip` next to the world folder). Players on vanilla clients get it only if
something serves it to them; that is Polymer autohost's job, configured in `config/polymer/auto-host.json` on the
server, which Polymer writes on first boot. `/foundry pack status` reports the zip's size and SHA-1.

`enabled` must be `true` and `type` picks the provider. Foundry calls
`PolymerResourcePackUtils.markAsRequired()`, so the pack is sent as required unless `mod_override` is set to false;
`required: true` in the file is belt and braces.

## Provider choice for the Kinetic server

Kinetic gives the server exactly one open port, the game port. In order of preference:

1. `polymer:netty` (also registered as `polymer:same_port`): serves the zip over the game port itself by watching
   the first bytes of each connection. No extra port needed. Set `settings.forced_address` to the address players
   type into the client, including the port when it is not 25565, for example `myserver.example.net` or
   `myserver.example.net:25570`. The panel's internal address is never right for this field.
2. `polymer:automatic` (the default): picks a provider on its own, and will try the same-port path. Give it the
   same `forced_address`. Keep this if you do not want to think about it, but check that the pack actually
   downloads; fall back to `polymer:netty` when it guesses wrong.
3. `polymer:external`: hands clients a URL where the zip lives, `settings.address` being the full `https://...`
   link. Works when nothing else can: upload `polymer/resource_pack.zip` to any static host after every content
   change. The hash Polymer sends is the real file's, so a stale zip at the URL makes clients fail the download
   instead of silently using old content.
4. `polymer:http_server` / `polymer:standalone`: a small web server on `settings.port` serving at
   `settings.externalAddress`. Only an option if Kinetic opens a second port, which it does not by default.
5. `polymer:empty`: autohost sends nothing; the owner wires `resource-pack=` in server.properties instead. Last
   resort; the zip then has to be hosted elsewhere and its sha1 pasted into `resource-pack-sha1` by hand.

## After a pack change

The zip is rebuilt at server start. A restart plus the autohost settings above is enough: players re-download on
join. `include_hash_in_name` stays true so a rebuilt pack is a new URL, never a cached old one.

This file tells the owner what to set; nothing here is applied by the mod or written to the repo's `run/` config.
