# OpenCanard connector for DBeaver

A small DBeaver plugin that adds **OpenCanard** (with the logo from `resources/`) to the *New Database
Connection* dialog. It is a Generic JDBC data source with the OpenCanard JDBC driver preconfigured:

| Setting | Value |
| --- | --- |
| Driver class | `io.opencanard.driver.jdbc.OpenCanardDriver` |
| URL template | `jdbc:opencanard://{host}[:{port}]` |
| Default port | `8815` |

> The plugin could not be tested against a running DBeaver when it was written (see
> [If it does not show up](#if-it-does-not-show-up)). The icons are generated from
> `resources/opencanard_logo.png` (the duck and database, transparent background): `icons/opencanard_icon.png`
> (16x16) and `icons/opencanard_icon_big.png` (64x64).

## Build

1. Build the driver (it is packed into the plugin):

   ```powershell
   mvn -pl opencanard-jdbc-driver -am clean package
   ```

2. Build the plugin, from this folder (needs a JDK on the PATH):

   ```powershell
   .\build.ps1        # Windows
   ./build.sh         # Linux / macOS
   ```

   The result is `target/org.opencanard.dbeaver.connector_0.1.0.jar`. Without the driver jar from step 1 the
   plugin is built without it and you add the jar by hand in DBeaver (see below).

## Install

1. Close DBeaver.
2. Copy `org.opencanard.dbeaver.connector_0.1.0.jar` to the `dropins` folder of the DBeaver installation, for example
   `C:\Program Files\DBeaver\dropins\`.
3. Start DBeaver once with a clean plugin cache: `dbeaver.exe -clean`.
4. *Database > New Database Connection*, search for **OpenCanard**.

After a new version of the plugin or the driver, repeat the steps (the `-clean` start is needed again).

## Install without `dropins`

If the plugin is not listed after using `dropins` (the `dropins` folder is not always picked up), install it
straight into DBeaver. With DBeaver closed, from this folder:

```powershell
.\install.ps1                                      # DBeaver in %LOCALAPPDATA%\DBeaver
.\install.ps1 -DBeaverDir "C:\Program Files\DBeaver"  # or another installation
```

It copies the jar to `<DBeaver>\plugins`, adds one line to
`<DBeaver>\configuration\org.eclipse.equinox.simpleconfigurator\bundles.info` (a backup is saved next to it as
`bundles.info.opencanard.bak`), and you then start DBeaver once with `dbeaver.exe -clean`.
`.\install.ps1 -Uninstall` removes it again. Remove a jar you put in `dropins` first.

## If it does not show up

- Check *Help > About DBeaver > Installation Details > Plug-ins* for `org.opencanard.dbeaver.connector`. If it is
  missing, the jar is not in the right `dropins` folder.
- If the plugin is listed but there is no OpenCanard entry, look in `<workspace>/.metadata/.log`
  (*Help > Show Log*, or `%APPDATA%\DBeaverData\workspace6\.metadata\.log`) for errors mentioning
  `org.opencanard` and send them: the cause is usually the `plugin.xml` format of that DBeaver version.
- If DBeaver says the driver file is missing, open *Database > Driver Manager > OpenCanard > Libraries*, press
  *Add File* and pick `opencanard-jdbc-driver-0.1-all.jar` (use the `-all` jar, not the `original-` one).

Without the plugin you can still connect: create a driver by hand in *Driver Manager > New* with the class and
URL above. DBeaver does not let a manually created driver have a custom icon, which is why this plugin exists.

## Diagnosing "clicking does nothing"

A listed entry that does nothing when clicked usually lacks a connection page for its data source, or the class of
that page is not visible to the plugin. To see how this DBeaver version declares the Generic data source, run
(DBeaver closed or open, it only reads):

```powershell
$p = "$env:LOCALAPPDATA\DBeaver\plugins"
Get-ChildItem $p -Filter "org.jkiss.dbeaver.ext.generic*"          # which generic bundles exist
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($f in Get-ChildItem $p -Filter "org.jkiss.dbeaver.ext.generic*.jar") {
  $z = [IO.Compression.ZipFile]::OpenRead($f.FullName)
  $e = $z.GetEntry("plugin.xml"); if ($e) {
    $r = New-Object IO.StreamReader($e.Open()); "=== $($f.Name)"; ($r.ReadToEnd() -split "`n" | Select-String "dataSourceView|<view|class=.*Page|targetID") ; $r.Close() }
  $z.Dispose() }
```
