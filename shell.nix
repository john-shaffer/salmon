{ sources ? import ./nix/sources.nix, pkgs ? import sources.nixpkgs { } }:
let
  jdk = pkgs.openjdk17;
in with pkgs;
mkShell {
  buildInputs = [
    clj-kondo
    (clojure.override { jdk = jdk; })
    git
    polylith
    python39Packages.cfn-lint
    rlwrap # Used by clj
    time
  ];
}
