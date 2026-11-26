# Third-party notices

The SoundsGood Personal Use Only license applies only to project-controlled material.
It does not restrict the independent licenses or prior grants of third parties.
SoundsGood Full is separately distributed; its app license is not the Personal source license.

## Bundled fonts (SIL Open Font License 1.1)

Copyright 2020 The JetBrains Mono Project Authors (https://github.com/JetBrains/JetBrainsMono)
Copyright 2021 The Outfit Project Authors (https://github.com/Outfitio/Outfit-Fonts)
Copyright 2020 The Plus Jakarta Sans Project Authors (https://github.com/tokotype/PlusJakartaSans)

The unchanged font files retain their embedded attribution and license metadata.
The complete [OFL 1.1 text](docs/licenses/OFL-1.1.txt) accompanies them.

## Android software components

The declared Android implementation uses Kotlin, Kotlin coroutines and serialization;
AndroidX Activity, Core, Lifecycle, Compose, Navigation, Room and Media3; and Coil.
These projects use Apache License 2.0. Their transitive components include OkHttp, Okio
and Guava. Copyright remains with each upstream project's authors and contributors.
See the complete [Apache 2.0 text](docs/licenses/Apache-2.0.txt) and upstream notices
in the exact dependency artifacts. Build and test tools retain their respective licenses;
being in a version catalog does not mean a component is included in an application.

OkHttp includes Public Suffix List data under Mozilla Public License 2.0, separately
from its Apache-licensed code. The upstream source is https://publicsuffix.org/list/public_suffix_list.dat
and the license is https://www.mozilla.org/MPL/2.0/ . No Personal Use Only restriction
applies to that data. This repository does not modify it.

The Mac companion uses Apple's system frameworks, subject to Apple's applicable terms.
Those frameworks are not relicensed or supplied as source by this repository.

Project-generated artwork retains its embedded provenance metadata. It is separate from
user-selected music and artwork, which remain the responsibility of their rights holders.

This source notice identifies the declared component families. A distributed binary must
also retain the notices and license obligations of its exact resolved dependency versions.

## Resolved Personal runtime attribution

The offline release runtime resolution contains 94 coordinates and 95 artifacts,
with exact versions and hashes in [the inventory](docs/licenses/personal-runtime-dependencies.json).
It also includes Accompanist Drawable Painter, JetBrains annotations and JSpecify.
All artifact POMs declare Apache 2.0; OkHttp additionally retains the
[Public Suffix List notice](docs/licenses/OkHttp-Public-Suffix-NOTICE.txt) and
[complete MPL 2.0](docs/licenses/MPL-2.0.txt). All artifact archives, including
nested JARs, were checked for LICENSE/NOTICE/COPYING/COPYRIGHT entries. The
additional notice found was the Public Suffix List notice; other extracted
entries were the standard Apache license text. This inventory describes Personal
source resolution and does not certify any installed or published Full binary.
