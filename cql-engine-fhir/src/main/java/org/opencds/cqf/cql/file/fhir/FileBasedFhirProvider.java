package org.opencds.cqf.cql.file.fhir;

import org.hl7.fhir.dstu3.model.CodeableConcept;
import org.hl7.fhir.dstu3.model.Coding;
import org.hl7.fhir.dstu3.model.DateTimeType;
import org.joda.time.Partial;
import org.opencds.cqf.cql.data.fhir.BaseFhirDataProvider;
import org.opencds.cqf.cql.elm.execution.InEvaluator;
import org.opencds.cqf.cql.elm.execution.IncludesEvaluator;
import org.opencds.cqf.cql.runtime.Code;
import org.opencds.cqf.cql.runtime.DateTime;
import org.opencds.cqf.cql.runtime.Interval;
import org.opencds.cqf.cql.terminology.ValueSetInfo;
import org.opencds.cqf.cql.terminology.fhir.FhirTerminologyProvider;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URL;
import java.net.URLClassLoader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
What the heck does this thing do?
  This class is intended to provide the user with the alternative of using a local repository
  for clinical data retrieval instead of an external service.
  NOTE: This class still uses a Terminology service for value set retrieval and evaluating code membership

How do I use it?
  Point the provider to the directory of patients:
    FileBasedFhirProvider provider = new FileBasedFhirProvider(path to root data dir, terminology service endpoint(optional));
    Each subfolder name in the patients directory should be an id for a specific patients
      In each patient folder there should be subfolders contatining clinical information
        (e.g. Condition, Procedure, etc...) for that patient in JSON format (XML support pending)
    Here is a mock directory structure:
    - patients
      - 123
        - Conditions
          - JSON representation of Fhir resources
        - Procedures
        - Encounters
        - etc...
      - 154
        - Observations
        - etc...
      - 209
      - etc...
*/

public class FileBasedFhirProvider extends BaseFhirDataProvider {

  private Path path;
  private FhirTerminologyProvider terminologyProvider;

  public FileBasedFhirProvider (String path, String endpoint) {
    if (path.isEmpty()) {
      throw new InvalidPathException(path, "Invalid path!");
    }
    this.path = Paths.get(path);
    this.terminologyProvider = endpoint == null ? new FhirTerminologyProvider().withEndpoint("http://fhirtest.uhn.ca/baseDstu3")
                                                : new FhirTerminologyProvider().withEndpoint(endpoint);
  }

  private URL pathToModelJar;
  public void setPathToModelJar(URL pathToModelJar) {
    this.pathToModelJar = pathToModelJar;
  }

  public FileBasedFhirProvider withPathToModelJar(URL pathToModelJar) {
    setPathToModelJar(pathToModelJar);
    return this;
  }

  public Iterable<Object> retrieve(String context, Object contextValue, String dataType, String templateId,
                                   String codePath, Iterable<Code> codes, String valueSet, String datePath,
                                   String dateLowPath, String dateHighPath, Interval dateRange) {

    List<Object> results = new ArrayList<>();
    List<String> patientFiles;
    Path toResults = path;

    // default context is Patient
    if (context == null) {
      context = "Patient";
    }

    if (templateId != null && !templateId.equals("")) {
      // TODO: do something?
    }

    if (codePath == null && (codes != null || valueSet != null)) {
        throw new IllegalArgumentException("A code path must be provided when filtering on codes or a valueset.");
    }

    if (context != null && context.equals("Patient") && contextValue != null) {
      toResults = toResults.resolve((String)contextValue);
    }

    // Need the context value (patient id) to resolve the toResults path correctly
    else if (context.equals("Patient") && contextValue == null) {
      toResults = toResults.resolve(getDefaultPatient(toResults));
    }

    if (dataType != null) {
      // TODO: this isn't right - Patient is a valid fhir resource.
      if (!dataType.equals("Patient"))
        toResults = toResults.resolve(dataType.toLowerCase());
    }
    else { // Just in case -- probably redundant error checking...
      throw new IllegalArgumentException("A data type (i.e. Procedure, Valueset, etc...) must be specified for clinical data retrieval");
    }

    // No filtering
    if (dateRange == null && codePath == null) {
      patientFiles = getPatientFiles(toResults, context);
      for (String resource : patientFiles) {
        results.add(fhirContext.newJsonParser().parseResource(resource));
      }
      return results;
    }

    patientFiles = getPatientFiles(toResults, context);

    // filtering
    // NOTE: retrieves can include both date and code filtering,
    // so even though I may include a record if it is within the date range,
    // that record may be excluded later during the code filtering stage
    for (String resource : patientFiles) {
      Object res = fhirContext.newJsonParser().parseResource(resource);

      // since retrieves can include both date and code filtering, I need this flag
      // to determine inclusion of codes -- if date is no good -- don't test code
      boolean includeRes = true;

      // dateRange element optionally allows a date range to be provided.
      // The clinical statements returned would be only those clinical statements whose date
      // fell within the range specified.
      if (dateRange != null) {

        // Expand Interval DateTimes to avoid InEvalutor returning null
        // TODO: account for possible null for high or low? - No issues with this yet...
        Interval expanded = new Interval(
                                  DateTime.expandPartialMin((DateTime)dateRange.getLow(), 7), true,
                                  DateTime.expandPartialMin((DateTime)dateRange.getHigh(), 7), true
                                  );
        if (datePath != null) {
          if (dateHighPath != null || dateLowPath != null) {
            throw new IllegalArgumentException("If the datePath is specified, the dateLowPath and dateHighPath attributes must not be present.");
          }

          DateTime date = null;
          Object temp = resolvePath(res, datePath);

          // Hmm... May not need this.
          if (temp instanceof DateTime) {
            date = (DateTime)temp;
          }
          else if (temp instanceof DateTimeType) {
            date = toDateTime((DateTimeType)temp);
          }

          if (date != null && InEvaluator.in(date, expanded)) {
            results.add(res);
          }
          else {
            includeRes = false;
          }
        }

        else {
          if (dateHighPath == null && dateLowPath == null) {
            throw new IllegalArgumentException("If the datePath is not given, either the lowDatePath or highDatePath must be provided.");
          }

          // get the high and low dates if present
          // if not present, set to corresponding value in the expanded Interval
          DateTime highDt = dateHighPath != null ? (DateTime)resolvePath(res, dateHighPath) : (DateTime)expanded.getHigh();
          DateTime lowDt = dateLowPath != null ? (DateTime)resolvePath(res, dateLowPath) : (DateTime)expanded.getLow();

          // the low and high dates are resolved -- create the Interval
          Interval highLowDtInterval = new Interval(lowDt, true, highDt, true);

          // Now the Includes operation
          if ((Boolean)IncludesEvaluator.includes(expanded, highLowDtInterval)) {
              results.add(res);
          }
          else {
              includeRes = false;
          }
        }
      }

      // codePath specifies which property/path of the model contains the Code or Codes for the clinical statement
      if (codePath != null && !codePath.equals("") && includeRes) {
        if (valueSet != null && !valueSet.equals("")) {
          // now we need to get the codes in the resource and check for membership in the valueset
          Object resCodes = resolvePath(res, codePath);
          if (resCodes instanceof Iterable) {
            for (Object codeObj : (Iterable)resCodes) {
              boolean inValSet = checkCodeMembership(codeObj, valueSet);
              if (inValSet && results.indexOf(res) == -1)
                results.add(res);
              else if (!inValSet)
                results.remove(res);
            }
          }
          else if (resCodes instanceof CodeableConcept) {
            if (checkCodeMembership(resCodes, valueSet) && results.indexOf(res) == -1)
              results.add(res);
          }
        }
        else if (codes != null) {
          for (Code code : codes) {
            Object resCodes = resolvePath(res, codePath);
            if (resCodes instanceof Iterable) {
              for (Object codeObj : (Iterable)resCodes) {
                Iterable<Coding> conceptCodes = ((CodeableConcept)codeObj).getCoding();
                for (Coding c : conceptCodes) {
                  if (c.getCodeElement().getValue().equals(code.getCode()) && c.getSystem().equals(code.getSystem()))
                  {
                    if (results.indexOf(res) == -1)
                      results.add(res);
                  }
                  else if (results.indexOf(res) != -1)
                    results.remove(res);
                }
              }
            }
            else if (resCodes instanceof CodeableConcept) {
              for (Coding c : ((CodeableConcept)resCodes).getCoding()) {
                if (c.getCodeElement().getValue().equals(code.getCode()) && c.getSystem().equals(code.getSystem()))
                {
                  if (results.indexOf(res) == -1)
                    results.add(res);
                }
                else if (results.indexOf(res) != -1)
                  results.remove(res);
              }
            }
          }
        }
      }
    } // end of filtering for each loop

    return results;
  }

  // If Patient context without patient id, get the first patient
  public String getDefaultPatient(Path evalPath) {
    File file = new File(evalPath.toString());
    if (!file.exists()) {
      throw new IllegalArgumentException("Invalid path: " + evalPath.toString());
    }
    else if (file.listFiles().length == 0) {
      throw new IllegalArgumentException("The target directory is empty!");
    }

    return file.listFiles()[0].getName();
  }

  // evalPath examples -- NOTE: this occurs before filtering
  // ..../data/procedure -- all procedures for all patients (Population context)
  // ..../data/123/procedure -- all procedures for patient 123
  public List<String> getPatientFiles(Path evalPath, String context) {
    List<String> fileContents = new ArrayList<>();
    if (context.equals("Patient") || context.equals("") || context == null) {
      File file = new File(evalPath.toString());

      if (!file.exists()) {
        return fileContents;
      }

      try {
        for (File f : file.listFiles()) {
          if (f.getName().contains(".json"))
            fileContents.add(readFile(f));
        }
      }
      catch(NullPointerException npe) {
        throw new IllegalArgumentException("The target directory is empty!");
      }
    }
    else { // Population
      File rootDir = new File(path.toString());
      for (File patientFolder : rootDir.listFiles()) { // all the patients in data set
        for (File patientSubFolder : patientFolder.listFiles()) { // all the folders in the patient directory
          if (!patientSubFolder.isDirectory()) { continue; }
          // find the data type directory (condition, encounter, etc...)
          if (patientSubFolder.getName().equals(evalPath.getName(evalPath.getNameCount() - 1).toString())) {
            for (File dataTypeFile : patientSubFolder.listFiles()) {
              if (dataTypeFile.getName().contains(".json"))
                fileContents.add(readFile(dataTypeFile));
            }
          }
        }
      }
    }
    return fileContents;
  }

  public String readFile(File f) {
    StringBuilder fileContent = new StringBuilder();
    // try with resources -- automatically closes files once read -- cool =)
    try (BufferedReader data = new BufferedReader(new FileReader(f))) {
      String line;
      while ((line = data.readLine()) != null) {
        fileContent.append(line);
      }
    }
    catch (IOException e) {
      throw new IllegalArgumentException("File not found at path " + f.getPath());
    }
    return fileContent.toString();
  }

  public boolean checkCodeMembership(Object codeObj, String vsId) {
    Iterable<Coding> conceptCodes = ((CodeableConcept)codeObj).getCoding();
    for (Coding code : conceptCodes) {
      if (terminologyProvider.in(new Code()
                  .withCode(code.getCodeElement().getValue())
                  .withSystem(code.getSystem()),
                  new ValueSetInfo().withId(vsId)))
      {
        return true;
      }
    }
    return false;
  }

  public DateTime toDateTime(DateTimeType hapiDt) {
    // TODO: do we want 0 to be the default value if null?
    int year = hapiDt.getYear() == null ? 0 : hapiDt.getYear();
    // months in HAPI are zero-indexed -- don't want that, hence the plus one
    int month = hapiDt.getMonth() == null ? 0 : hapiDt.getMonth() + 1;
    int day = hapiDt.getDay() == null ? 0 : hapiDt.getDay();
    int hour = hapiDt.getHour() == null ? 0 : hapiDt.getHour();
    int minute = hapiDt.getMinute() == null ? 0 : hapiDt.getMinute();
    int sec = hapiDt.getSecond() == null ? 0 : hapiDt.getSecond();
    int millis = hapiDt.getMillis() == null ? 0 : hapiDt.getMillis();
    return new DateTime().withPartial(new Partial(DateTime.getFields(7), new int[] {year, month, day, hour, minute, sec, millis}));
  }
}
