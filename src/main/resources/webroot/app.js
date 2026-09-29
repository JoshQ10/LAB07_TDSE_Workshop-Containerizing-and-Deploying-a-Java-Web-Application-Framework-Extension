function greet() {
    const name = document.getElementById("name").value;
    const result = document.getElementById("result");
    result.textContent = "Cargando...";

    fetch("/hello?name=" + encodeURIComponent(name))
        .then(response => response.text())
        .then(message => {
            result.textContent = message;
        })
        .catch(error => {
            result.textContent = "Error: " + error;
        });
}

function getPi() {
    const result = document.getElementById("pi-result");
    result.textContent = "Cargando...";

    fetch("/pi")
        .then(response => response.text())
        .then(value => {
            result.textContent = "Pi = " + value;
        })
        .catch(error => {
            result.textContent = "Error: " + error;
        });
}

function getDate() {
    const result = document.getElementById("date-result");
    result.textContent = "Cargando...";

    fetch("/date")
        .then(response => response.text())
        .then(value => {
            result.textContent = value;
        })
        .catch(error => {
            result.textContent = "Error: " + error;
        });
}
