# Local `pulsar-client` bundle

## Overview

Bundle was created by overwriting pulsar-client-original jar with new OSGI-aware MANIFEST.MF file. 

## OSGi dependencies

For the bundle to work in OSGi container it was necessary to configure missing dependencies, which is done by defining `pulsar-client` feature, see the file `${project_dir}/cumulocity-application/cumulocity-core-karaf/src/main/resources/system/cumulocity-3rd-party-dependencie-feature.xml`. 

Note (1): those are not all the dependencies (at least not at the moment), only those missing for pulsar-client bundle to work. We can define all the dependencies, but it is not necessary.

Note (2): some dependencies are inlined (packages copied directly to resulting jar) in the resulting bundle (all the packages defined in `Export-Package` and `Private-Package` are inlined even if are coming from the dependencies). That's why we there were no need to define dependency for e.g. `pulsar-common` or `pulsar-client-api`.

### Overridden json-module-jsonSchema OSGi bundle

The jar json-module-jsonSchema already comes as OSGi bundle, but with `Import-package` dependency to `javax.validation.constra
ints;version="[1.1,2)"` which conflicts with `javax.validation`'s version 1.0.0 we're using in other places. To overcome that it was necessary to override this bundle with removed import to `javax.validation.constraints` -- fortunately this package is only used in test code.

## Updating the `pulsar-client` bundle

Updating the bundle should be straightforward. We need to:
* update `pulsar-client.version` property in `cumulocity-root`'s `pom.xml`
* go through `pulsar-client` OSGi dependencies to see if they require updating by comparing with maven dependencies
* test by starting the karaf, if there is an error with bundle wiring: add missing dependency to `pulsar-client` feature
* run functional test involving reliable notifications / forwarding
