packer {
  required_plugins {
    amazon = {
      source  = "github.com/hashicorp/amazon"
      version = "~> 1"
    }
  }
}

variable "region" {
  type = string
}

variable "source_ami" {
  type = string
}

variable "uberjar_path" {
  type = string
}

locals { timestamp = regex_replace(timestamp(), "[- TZ:]", "") }

source "amazon-ebs" "autogenerated_1" {
  ami_name = "Uberjar Server ${local.timestamp}"
  instance_type = "t3a.micro"
  region = var.region
  source_ami = var.source_ami
  ssh_username = "admin"
}

build {
  sources = ["source.amazon-ebs.autogenerated_1"]

  provisioner "file" {
    destination = "/home/admin/uberjar.service"
    source = "uberjar.service"
  }

  provisioner "shell" {
    inline = [
      "sudo apt-get update && sudo apt-get upgrade -y",
      "sudo apt-get install openjdk-17-jre-headless -y",
      "sudo apt-get autoremove -y"
    ]
  }

  provisioner "file" {
    destination = "/home/admin/uberjar.jar"
    source = var.uberjar_path
  }

  provisioner "shell" {
    inline = [
      "sudo chown root:root uberjar.service",
      "sudo mv uberjar.service /etc/systemd/system",
      "sudo systemctl daemon-reload",
      "sudo systemctl enable uberjar"
    ]
  }

}
