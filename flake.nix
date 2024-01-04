{
  description = "salmon";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-23.11";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils, ... }@inputs:
    flake-utils.lib.eachDefaultSystem (system:
      with import nixpkgs { inherit system; }; {
        devShells.default = mkShell {
          buildInputs = [
            clj-kondo
            clojure
            git
            jdk
            neil
            packer
            python39Packages.cfn-lint
            rlwrap # Used by clj
            time
          ];
        };
      });
}
