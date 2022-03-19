let
  rev = "2128d0aa28edef51fd8fef38b132ffc0155595df";
  nixpkgs = fetchTarball {
    url = "https://github.com/NixOS/nixpkgs/archive/${rev}.tar.gz";
    sha256 = "1slxx8rc7kfm61826cjbz2wz18xc9sxhg1ki8b6254gizgh5gvw4";
  };
  pkgs = import nixpkgs {};
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
