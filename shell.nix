let
  rev = "af0a9bc0e5341855518e9c1734d7ef913e5138b9";
  nixpkgs = fetchTarball {
    url = "https://github.com/NixOS/nixpkgs/archive/${rev}.tar.gz";
    sha256 = "0qqxa8xpy1k80v5al45bsxqfs3n6cphm9nki09q7ara7yf7yyrh1";
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
