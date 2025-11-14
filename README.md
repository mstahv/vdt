# VDT - A GUI with steroids for mvn dependency:tree

A mostly "Claude coded" visual replacement for `mvn dependency:tree`.

Online version deployed on a tiny server here: https://vdt.virit.in

## Install as local tool to workstation

Online version naturally can't investigate your private dependencies. In other ways as well, it might feel more secure if you run the app locally as a CLI tool. As you probably already have Java tooling on your app you can build a local version like this:

 1. Checkout the sources
 1. Build: `mvn package`
 1. Make it available as CLI tool, two examples
    * A: With JBang:
      1. Make sure you have JBang set up
      1. Install from the project directory: 
      ```bash
       jbang app install --name vdt target/vdt-1.0.0-SNAPSHOT-runner.jar
      ```
    * B: Native image to PATH
      1. Build native image:
      ```bash
      mvn install -Dnative
      ```
      1. Move the built native binary a directory on your PATH, e.g.:
      ```bash
      sudo mv target/vdt-1.0.0-SNAPSHOT-runner /usr/local/bin/vdt
      ```
      
 1. Use `vdt` as a replacement for `mvn dependency:tree` -> a new browser windows with a VDT for the pom.xml from your active directery
 1. Closing the browser window closes the local server (or take it down with CTRL-C from CLI)

