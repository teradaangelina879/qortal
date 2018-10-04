
import api.ApiClient;
import api.ApiService;
import repository.DataException;
import repository.RepositoryFactory;
import repository.RepositoryManager;
import repository.hsqldb.HSQLDBRepositoryFactory;

public class Start {

	private static final String connectionUrl = "jdbc:hsqldb:mem:db/test;create=true;close_result=true;sql.strict_exec=true;sql.enforce_names=true;sql.syntax_mys=true";

	public static void main(String args[]) throws DataException {
		RepositoryFactory repositoryFactory = new HSQLDBRepositoryFactory(connectionUrl);
		RepositoryManager.setRepositoryFactory(repositoryFactory);

		ApiService apiService = ApiService.getInstance();
		apiService.start();

		//// testing the API client
		ApiClient client = ApiClient.getInstance();
		String test = client.executeCommand("GET blocks/height");
		System.out.println(test);
	}
}
