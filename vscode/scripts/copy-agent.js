// Copies the shared agent (built by Gradle in ../agent) into the extension before packaging.
const fs = require('fs');
const path = require('path');

const source = path.join(__dirname, '..', '..', 'agent', 'build', 'libs', 'mydebug-agent.jar');
const target = path.join(__dirname, '..', 'agent', 'mydebug-agent.jar');

if (!fs.existsSync(source)) {
    console.error(`Agent jar not found: ${source}\nBuild it first: gradlew :agent:jar`);
    process.exit(1);
}
fs.mkdirSync(path.dirname(target), { recursive: true });
fs.copyFileSync(source, target);
console.log(`copied ${path.relative(process.cwd(), source)} -> ${path.relative(process.cwd(), target)}`);
